import redis4j.server.PersistenceOptions;
import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * REQ-AOF-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①appendonly 재시작 복원 ②사람이 읽는 RESP
 * ③BGREWRITEAOF 크기 감소·재생 동일 + fsync always 내구성 · AOF 우선(RDB보다).
 * 스냅샷/AOF 는 임시 디렉터리 경로 → 작업 디렉터리 오염 없음.
 */
public final class VerifyAof {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("aof-verify");
        try {
            aofRestartRestores(dir);        // ① + ②
            bgrewriteShrinks(dir);          // ③
            alwaysFsyncDurable(dir);        // fsync always
            aofPrecedenceOverRdb(dir);      // AOF 우선
        } finally {
            deleteRecursive(dir);
        }

        System.out.println("---");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static PersistenceOptions aofOpts(Path dir, String name, String fsync) {
        return new PersistenceOptions(dir.resolve(name + ".rdb4j"), true,
                dir.resolve(name + ".aof"), fsync, true);
    }

    // ① appendonly on 상태에서 쓰기 후 재시작 → 복원 + ② RESP 가독성
    private static void aofRestartRestores(Path dir) throws Exception {
        PersistenceOptions opts = aofOpts(dir, "case1", "everysec");
        RedisServer s1 = new RedisServer(0, opts);
        int p1 = s1.start();
        try (Client c = new Client(p1)) {
            c.send(req("SET", "s", "hello"));
            c.send(req("RPUSH", "l", "a", "b", "c"));
            c.send(req("HSET", "h", "f1", "v1", "f2", "v2"));
            c.send(req("SADD", "st", "m1", "m2"));
            c.send(req("ZADD", "z", "1", "a", "2", "b"));
            c.send(req("SELECT", "1"));
            c.send(req("SET", "d1", "v1"));
            c.send(req("SELECT", "0"));
        }
        s1.close();

        // ② 파일이 사람이 읽는 RESP 명령열
        String text = Files.readString(dir.resolve("case1.aof"), StandardCharsets.UTF_8);
        checkTrue("② AOF 가 RESP 로 시작(*)", text.startsWith("*"), text.substring(0, Math.min(8, text.length())));
        checkTrue("② AOF 에 SET/RPUSH RESP 포함", text.contains("SET") && text.contains("RPUSH"), "");
        checkTrue("② AOF 에 SELECT(db1) 기록", text.contains("SELECT"), "");

        RedisServer s2 = new RedisServer(0, opts);
        int p2 = s2.start();
        try (Client c = new Client(p2)) {
            check("① GET s -> hello", blk("hello"), c.send(req("GET", "s")));
            checkTree("① LRANGE l -> [a,b,c]", list("a", "b", "c"), c.sendTree(req("LRANGE", "l", "0", "-1")));
            checkTree("① HGETALL h", list("f1", "v1", "f2", "v2"), c.sendTree(req("HGETALL", "h")));
            checkTree("① SMEMBERS st", list("m1", "m2"), c.sendTree(req("SMEMBERS", "st")));
            checkTree("① ZRANGE z WS", list("a", "1", "b", "2"), c.sendTree(req("ZRANGE", "z", "0", "-1", "WITHSCORES")));
            c.send(req("SELECT", "1"));
            check("① db1 격리 복원 GET d1 -> v1", blk("v1"), c.send(req("GET", "d1")));
        }
        s2.close();
    }

    // ③ BGREWRITEAOF 후 파일 크기 감소·재생 결과 동일
    private static void bgrewriteShrinks(Path dir) throws Exception {
        PersistenceOptions opts = aofOpts(dir, "case3", "everysec");
        Path aofPath = dir.resolve("case3.aof");
        long sizeBefore;
        long sizeAfter;
        RedisServer s1 = new RedisServer(0, opts);
        int p1 = s1.start();
        try (Client c = new Client(p1)) {
            for (int i = 0; i < 100; i++) {
                c.send(req("INCR", "c"));                        // 중복 많은 로그
            }
            c.send(req("SET", "k", "v1"));
            c.send(req("SET", "k", "v2"));
            sizeBefore = Files.size(aofPath);
            check("③ BGREWRITEAOF -> started",
                    "+Background append only file rewriting started\r\n", c.send(req("BGREWRITEAOF")));
            sizeAfter = Files.size(aofPath);
            checkTrue("③ 재작성 후 파일 크기 감소 (" + sizeAfter + " < " + sizeBefore + ")",
                    sizeAfter < sizeBefore, "");
        }
        s1.close();

        RedisServer s2 = new RedisServer(0, opts);
        int p2 = s2.start();
        try (Client c = new Client(p2)) {
            check("③ 재작성분 재생 GET c -> 100", blk("100"), c.send(req("GET", "c")));
            check("③ 재작성분 재생 GET k -> v2", blk("v2"), c.send(req("GET", "k")));
        }
        s2.close();
    }

    // fsync always: 쓰기 즉시 디스크 반영 → 재시작 복원(내구성)
    private static void alwaysFsyncDurable(Path dir) throws Exception {
        PersistenceOptions opts = aofOpts(dir, "case4", "always");
        RedisServer s1 = new RedisServer(0, opts);
        int p1 = s1.start();
        try (Client c = new Client(p1)) {
            c.send(req("SET", "dur", "1"));
        }
        s1.close();
        RedisServer s2 = new RedisServer(0, opts);
        int p2 = s2.start();
        try (Client c = new Client(p2)) {
            check("fsync always 재시작 복원 GET dur -> 1", blk("1"), c.send(req("GET", "dur")));
        }
        s2.close();
    }

    // AOF 우선: RDB·AOF 공존 시 기동은 AOF 재생
    private static void aofPrecedenceOverRdb(Path dir) throws Exception {
        PersistenceOptions opts = aofOpts(dir, "case5", "everysec");
        RedisServer s1 = new RedisServer(0, opts);
        int p1 = s1.start();
        try (Client c = new Client(p1)) {
            c.send(req("SET", "a", "1"));
            c.send(req("SAVE"));                                 // RDB 스냅샷(a=1)
            c.send(req("SET", "b", "2"));                        // AOF 에만 추가 반영
        }
        s1.close();
        RedisServer s2 = new RedisServer(0, opts);
        int p2 = s2.start();
        try (Client c = new Client(p2)) {
            check("AOF 우선: b(=AOF 이후 쓰기) 복원 -> 2", blk("2"), c.send(req("GET", "b")));
            check("AOF 우선: a 복원 -> 1", blk("1"), c.send(req("GET", "a")));
        }
        s2.close();
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted((x, y) -> y.getNameCount() - x.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 무시
                }
            });
        }
    }

    private static List<Object> list(Object... items) {
        return Arrays.asList(items);
    }

    private static byte[] req(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "*" + parts.length + "\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            write(out, "$" + b.length + "\r\n");
            out.writeBytes(b);
            write(out, "\r\n");
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String blk(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return "$" + b.length + "\r\n" + s + "\r\n";
    }

    private static void check(String label, String expect, String actual) {
        if (expect.equals(actual)) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + esc(expect) + " actual=" + esc(actual));
        }
    }

    private static void checkTree(String label, Object expect, Object actual) {
        if (expect.equals(actual)) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + expect + " actual=" + actual);
        }
    }

    private static void checkTrue(String label, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | " + esc(detail));
        }
    }

    private static String esc(String s) {
        return s == null ? "null" : s.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Client(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            in = socket.getInputStream();
            out = socket.getOutputStream();
        }

        String send(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            return readReply();
        }

        Object sendTree(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            return readTree();
        }

        private Object readTree() throws IOException {
            char t = (char) in.read();
            String head = readLine();
            switch (t) {
                case '+', '-':
                    return t + head;
                case ':':
                    return Long.parseLong(head.trim());
                case '$': {
                    int len = Integer.parseInt(head.trim());
                    if (len < 0) {
                        return null;
                    }
                    byte[] body = in.readNBytes(len);
                    in.read();
                    in.read();
                    return new String(body, StandardCharsets.UTF_8);
                }
                case '*': {
                    int cnt = Integer.parseInt(head.trim());
                    if (cnt < 0) {
                        return null;
                    }
                    List<Object> l = new ArrayList<>(cnt);
                    for (int i = 0; i < cnt; i++) {
                        l.add(readTree());
                    }
                    return l;
                }
                default:
                    return head;
            }
        }

        private String readReply() throws IOException {
            int type = in.read();
            if (type == -1) {
                return "";
            }
            char t = (char) type;
            String head = readLine();
            String prefix = t + head + "\r\n";
            switch (t) {
                case '+', '-', ':':
                    return prefix;
                case '$': {
                    int len = Integer.parseInt(head.trim());
                    if (len < 0) {
                        return prefix;
                    }
                    byte[] body = in.readNBytes(len);
                    in.read();
                    in.read();
                    return prefix + new String(body, StandardCharsets.UTF_8) + "\r\n";
                }
                case '*': {
                    int cnt = Integer.parseInt(head.trim());
                    if (cnt < 0) {
                        return prefix;
                    }
                    StringBuilder sb = new StringBuilder(prefix);
                    for (int i = 0; i < cnt; i++) {
                        sb.append(readReply());
                    }
                    return sb.toString();
                }
                default:
                    return prefix;
            }
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') {
                    in.read();
                    break;
                }
                if (c == '\n') {
                    break;
                }
                sb.append((char) c);
            }
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
