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
 * REQ-RDB-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①SAVE→파일 ②재시작→키·값·TTL 복원
 * ③원자적 저장(임시파일 잔존 없음·재저장 후 온전) ④빈 데이터셋 + 전 자료형·다중 DB·BGSAVE·LASTSAVE.
 * 스냅샷은 임시 디렉터리 경로를 써서 작업 디렉터리를 오염시키지 않는다.
 */
public final class VerifyRdb {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("rdb4j-verify");
        Path rdbPath = dir.resolve("dump.rdb4j");               // 아직 없음 → server1 빈 상태로 기동
        try {
            // === 적재 + SAVE (①) : 전 자료형·다중 DB·TTL ===
            RedisServer s1 = new RedisServer(0, rdbPath);
            int p1 = s1.start();
            try (Client c = new Client(p1)) {
                c.send(req("SET", "s", "hello"));
                c.send(req("RPUSH", "l", "a", "b", "c"));
                c.send(req("HSET", "h", "f1", "v1", "f2", "v2"));
                c.send(req("SADD", "st", "m1", "m2"));
                c.send(req("ZADD", "z", "1", "a", "2", "b"));
                c.send(req("SET", "tvol", "x", "PX", "100000"));  // TTL
                c.send(req("SELECT", "1"));
                c.send(req("SET", "d1key", "d1val"));             // db1 격리 데이터
                c.send(req("SELECT", "0"));
                check("① SAVE -> +OK", "+OK\r\n", c.send(req("SAVE")));
                checkTrue("① 스냅샷 파일 생성", Files.exists(rdbPath), "");
                checkTrue("③ 임시파일 잔존 없음(원자적 rename)", noTmpLeft(dir), "");
                String last = c.send(req("LASTSAVE"));
                checkTrue("LASTSAVE -> 양의 정수", last.startsWith(":") && intVal(last) > 0, last);
            }
            s1.close();

            // === 재시작 → 복원 (②) ===
            RedisServer s2 = new RedisServer(0, rdbPath);
            int p2 = s2.start();
            try (Client c = new Client(p2)) {
                check("② GET s -> hello", blk("hello"), c.send(req("GET", "s")));
                checkTree("② LRANGE l -> [a,b,c]", list("a", "b", "c"), c.sendTree(req("LRANGE", "l", "0", "-1")));
                checkTree("② HGETALL h -> f1 v1 f2 v2", list("f1", "v1", "f2", "v2"),
                        c.sendTree(req("HGETALL", "h")));
                checkTree("② SMEMBERS st -> [m1,m2]", list("m1", "m2"), c.sendTree(req("SMEMBERS", "st")));
                checkTree("② ZRANGE z WITHSCORES -> [a,1,b,2]", list("a", "1", "b", "2"),
                        c.sendTree(req("ZRANGE", "z", "0", "-1", "WITHSCORES")));
                String pttl = c.send(req("PTTL", "tvol"));
                long ms = intVal(pttl);
                checkTrue("② TTL 복원 (0 < PTTL <= 100000)", ms > 0 && ms <= 100000, pttl);
                check("② DBSIZE(db0) -> 6", ":6\r\n", c.send(req("DBSIZE")));
                c.send(req("SELECT", "1"));
                check("② db1 격리 복원 GET d1key -> d1val", blk("d1val"), c.send(req("GET", "d1key")));
            }
            s2.close();

            // === 빈 데이터셋 저장/로드 (④) ===
            Path emptyPath = dir.resolve("empty.rdb4j");
            RedisServer s3 = new RedisServer(0, emptyPath);
            int p3 = s3.start();
            try (Client c = new Client(p3)) {
                check("④ 빈 SAVE -> +OK", "+OK\r\n", c.send(req("SAVE")));
                checkTrue("④ 빈 스냅샷 파일 생성", Files.exists(emptyPath), "");
                check("④ DBSIZE -> 0", ":0\r\n", c.send(req("DBSIZE")));
            }
            s3.close();
            RedisServer s4 = new RedisServer(0, emptyPath);
            int p4 = s4.start();
            try (Client c = new Client(p4)) {
                check("④ 빈 스냅샷 로드 후 DBSIZE -> 0", ":0\r\n", c.send(req("DBSIZE")));
            }
            s4.close();

            // === 재저장 후에도 파일 온전·잔존 임시파일 없음 (③) ===
            RedisServer s5 = new RedisServer(0, rdbPath);
            int p5 = s5.start();
            try (Client c = new Client(p5)) {
                c.send(req("SET", "s", "changed"));
                check("③ 재-SAVE -> +OK", "+OK\r\n", c.send(req("SAVE")));
                checkTrue("③ 재저장 후 임시파일 없음", noTmpLeft(dir), "");
            }
            s5.close();
            RedisServer s6 = new RedisServer(0, rdbPath);
            int p6 = s6.start();
            try (Client c = new Client(p6)) {
                check("③ 재저장분 로드 GET s -> changed", blk("changed"), c.send(req("GET", "s")));
            }
            s6.close();

            // === BGSAVE (백그라운드) ===
            Path bgPath = dir.resolve("bg.rdb4j");
            RedisServer s7 = new RedisServer(0, bgPath);
            int p7 = s7.start();
            try (Client c = new Client(p7)) {
                c.send(req("SET", "bk", "bv"));
                check("BGSAVE -> +Background saving started", "+Background saving started\r\n",
                        c.send(req("BGSAVE")));
                boolean appeared = false;
                for (int i = 0; i < 40 && !appeared; i++) {       // 최대 ~2s 대기
                    if (Files.exists(bgPath)) {
                        appeared = true;
                    } else {
                        Thread.sleep(50);
                    }
                }
                checkTrue("BGSAVE 파일 생성됨", appeared, "");
            }
            s7.close();
            RedisServer s8 = new RedisServer(0, bgPath);
            int p8 = s8.start();
            try (Client c = new Client(p8)) {
                check("BGSAVE분 로드 GET bk -> bv", blk("bv"), c.send(req("GET", "bk")));
            }
            s8.close();
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

    private static boolean noTmpLeft(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        }
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

    private static long intVal(String reply) {
        return Long.parseLong(reply.substring(1, reply.length() - 2));
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
