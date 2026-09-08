import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * REQ-KEY-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①KEYS ②SCAN 순회 ③SELECT 격리 ④FLUSHDB
 * + glob(?,[]) · RENAME(NX) · RANDOMKEY · UNLINK · SWAPDB · SCAN MATCH/COUNT/TYPE.
 * 단일 영속 연결을 사용한다(SELECT 상태는 연결별이므로).
 */
public final class VerifyKey {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-key: redis4j on port " + port);
        try (Client c = new Client(port)) {
            c.send(req("FLUSHALL"));

            // ① MSET a 1 b 2; KEYS * -> {a,b}
            check("MSET a 1 b 2 -> +OK", "+OK\r\n", c.send(req("MSET", "a", "1", "b", "2")));
            checkSet("① KEYS * -> {a,b}", set("a", "b"), keySet(c, req("KEYS", "*")));
            checkSet("KEYS a -> {a}", set("a"), keySet(c, req("KEYS", "a")));

            // glob: ? 와 [] 와 *
            c.send(req("FLUSHDB"));
            c.send(req("MSET", "hello", "1", "hallo", "2", "hxllo", "3", "hllo", "4"));
            checkSet("KEYS h?llo -> {hello,hallo,hxllo}", set("hello", "hallo", "hxllo"),
                    keySet(c, req("KEYS", "h?llo")));
            checkSet("KEYS h[ae]llo -> {hello,hallo}", set("hello", "hallo"),
                    keySet(c, req("KEYS", "h[ae]llo")));
            checkSet("KEYS h*llo -> {hello,hallo,hxllo,hllo}", set("hello", "hallo", "hxllo", "hllo"),
                    keySet(c, req("KEYS", "h*llo")));

            // ② SCAN 커서 순회로 전 키 수집(COUNT 로 여러 라운드 강제)
            c.send(req("FLUSHDB"));
            String[] mset = new String[21];
            mset[0] = "MSET";
            TreeSet<String> expected = new TreeSet<>();
            for (int i = 0; i < 10; i++) {
                mset[1 + i * 2] = "k" + i;
                mset[2 + i * 2] = "v";
                expected.add("k" + i);
            }
            c.send(req(mset));
            checkSet("② SCAN 0 COUNT 3 전체 순회 -> {k0..k9}", expected, scanAll(c, "3", null, null));
            checkSet("SCAN MATCH k1 -> {k1}", set("k1"), scanAll(c, null, "k1", null));

            // SCAN TYPE 필터
            c.send(req("LPUSH", "mylist", "x"));
            checkSet("SCAN TYPE list -> {mylist}", set("mylist"), scanAll(c, null, null, "list"));

            // DBSIZE / RANDOMKEY
            check("DBSIZE -> :11 (k0..k9 + mylist)", ":11\r\n", c.send(req("DBSIZE")));
            String rnd = bulkVal(c.send(req("RANDOMKEY")));
            checkTrue("RANDOMKEY -> 기존 키 중 하나", rnd != null && (expected.contains(rnd) || rnd.equals("mylist")),
                    "rnd=" + rnd);

            // RENAME / RENAMENX
            c.send(req("FLUSHDB"));
            c.send(req("SET", "src", "1"));
            check("RENAME src dst -> +OK", "+OK\r\n", c.send(req("RENAME", "src", "dst")));
            check("EXISTS src -> :0", ":0\r\n", c.send(req("EXISTS", "src")));
            check("GET dst -> 1", blk("1"), c.send(req("GET", "dst")));
            checkTrue("RENAME nokey x -> ERR no such key",
                    c.send(req("RENAME", "nokey", "x")).startsWith("-ERR no such key"), "");
            c.send(req("SET", "p", "1"));
            c.send(req("SET", "q", "2"));
            check("RENAMENX p q -> :0 (q 존재)", ":0\r\n", c.send(req("RENAMENX", "p", "q")));
            check("RENAMENX p r -> :1", ":1\r\n", c.send(req("RENAMENX", "p", "r")));
            check("EXISTS p -> :0", ":0\r\n", c.send(req("EXISTS", "p")));
            check("GET r -> 1", blk("1"), c.send(req("GET", "r")));

            // UNLINK (DEL 별칭)
            c.send(req("FLUSHDB"));
            c.send(req("MSET", "u1", "1", "u2", "2"));
            check("UNLINK u1 u2 u3 -> :2", ":2\r\n", c.send(req("UNLINK", "u1", "u2", "u3")));

            // ④ FLUSHDB -> DBSIZE 0
            c.send(req("SET", "leftover", "1"));
            check("FLUSHDB -> +OK", "+OK\r\n", c.send(req("FLUSHDB")));
            check("④ DBSIZE -> :0", ":0\r\n", c.send(req("DBSIZE")));

            // ③ SELECT 격리
            c.send(req("FLUSHALL"));
            c.send(req("SET", "g", "v0"));                       // db0
            check("SELECT 1 -> +OK", "+OK\r\n", c.send(req("SELECT", "1")));
            check("③ GET g (db1) -> nil (격리)", "$-1\r\n", c.send(req("GET", "g")));
            c.send(req("SET", "g", "v1"));                       // db1
            check("DBSIZE (db1) -> :1", ":1\r\n", c.send(req("DBSIZE")));
            check("GET g (db1) -> v1", blk("v1"), c.send(req("GET", "g")));
            check("SELECT 0 -> +OK", "+OK\r\n", c.send(req("SELECT", "0")));
            check("GET g (db0) -> v0", blk("v0"), c.send(req("GET", "g")));
            check("DBSIZE (db0) -> :1", ":1\r\n", c.send(req("DBSIZE")));

            // SWAPDB 0 1: 내용 교환
            check("SWAPDB 0 1 -> +OK", "+OK\r\n", c.send(req("SWAPDB", "0", "1")));
            check("GET g (db0, 교환 후) -> v1", blk("v1"), c.send(req("GET", "g")));
            c.send(req("SELECT", "1"));
            check("GET g (db1, 교환 후) -> v0", blk("v0"), c.send(req("GET", "g")));
            c.send(req("SELECT", "0"));

            // SELECT 범위 밖
            checkTrue("SELECT 16 -> ERR out of range",
                    c.send(req("SELECT", "16")).startsWith("-ERR DB index is out of range"), "");
            checkTrue("SELECT x -> ERR not integer",
                    c.send(req("SELECT", "x")).startsWith("-ERR value is not an integer"), "");
        } finally {
            server.close();
        }

        System.out.println("---");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    /** KEYS 결과를 집합으로. */
    private static TreeSet<String> keySet(Client c, byte[] request) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        Object o = c.sendTree(request);
        for (Object k : (List<?>) o) {
            out.add((String) k);
        }
        return out;
    }

    /** SCAN 0 부터 커서가 0 으로 돌아올 때까지 순회하며 키를 모은다. */
    private static TreeSet<String> scanAll(Client c, String count, String match, String type) throws IOException {
        TreeSet<String> acc = new TreeSet<>();
        String cursor = "0";
        do {
            List<String> parts = new ArrayList<>();
            parts.add("SCAN");
            parts.add(cursor);
            if (match != null) {
                parts.add("MATCH");
                parts.add(match);
            }
            if (count != null) {
                parts.add("COUNT");
                parts.add(count);
            }
            if (type != null) {
                parts.add("TYPE");
                parts.add(type);
            }
            Object o = c.sendTree(req(parts.toArray(new String[0])));
            List<?> l = (List<?>) o;
            cursor = (String) l.get(0);
            for (Object k : (List<?>) l.get(1)) {
                acc.add((String) k);
            }
        } while (!cursor.equals("0"));
        return acc;
    }

    private static TreeSet<String> set(String... items) {
        TreeSet<String> s = new TreeSet<>();
        for (String i : items) {
            s.add(i);
        }
        return s;
    }

    private static String bulkVal(String wire) {
        // "$<n>\r\n<body>\r\n" -> body; nil -> null
        if (wire.startsWith("$-1")) {
            return null;
        }
        int firstNl = wire.indexOf("\r\n");
        String body = wire.substring(firstNl + 2);
        return body.substring(0, body.length() - 2);
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

    private static void checkSet(String label, TreeSet<String> expect, TreeSet<String> actual) {
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
            int type = in.read();
            char t = (char) type;
            String head = readLine();
            switch (t) {
                case '+':
                    return "+" + head;
                case '-':
                    return "-" + head;
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
