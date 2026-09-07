import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** REQ-SET-FUNC-01 JDK 전용 검증 하니스. 집합 반환 명령은 순서 비의존(집합 비교)으로 판정. */
public final class VerifySet {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-set: redis4j on port " + port);
        try {
            // ① SADD(중복 무시) + SCARD
            check("① SADD s a b a -> :2", ":2\r\n", one(port, req("SADD", "s", "a", "b", "a")));
            check("① SCARD s -> :2", ":2\r\n", one(port, req("SCARD", "s")));

            // ② SISMEMBER / SMISMEMBER / SMEMBERS
            check("② SISMEMBER s a -> :1", ":1\r\n", one(port, req("SISMEMBER", "s", "a")));
            check("② SISMEMBER s z -> :0", ":0\r\n", one(port, req("SISMEMBER", "s", "z")));
            check("SMISMEMBER s a z -> [1,0]", "*2\r\n:1\r\n:0\r\n", one(port, req("SMISMEMBER", "s", "a", "z")));
            checkSet("SMEMBERS s -> {a,b}", one(port, req("SMEMBERS", "s")), "a", "b");

            // ③ 집합 연산
            one(port, req("SADD", "s1", "a", "b", "c"));
            one(port, req("SADD", "s2", "b", "c", "d"));
            checkSet("③ SINTER s1 s2 -> {b,c}", one(port, req("SINTER", "s1", "s2")), "b", "c");
            checkSet("SUNION s1 s2 -> {a,b,c,d}", one(port, req("SUNION", "s1", "s2")), "a", "b", "c", "d");
            checkSet("SDIFF s1 s2 -> {a}", one(port, req("SDIFF", "s1", "s2")), "a");

            // STORE 변형
            check("SINTERSTORE d s1 s2 -> :2", ":2\r\n", one(port, req("SINTERSTORE", "d", "s1", "s2")));
            checkSet("SMEMBERS d -> {b,c}", one(port, req("SMEMBERS", "d")), "b", "c");
            check("TYPE d -> +set", "+set\r\n", one(port, req("TYPE", "d")));
            check("SDIFFSTORE d2 s2 s1 s2 -> :0(공집합)", ":0\r\n", one(port, req("SDIFFSTORE", "d2", "s2", "s1", "s2")));
            check("EXISTS d2 -> :0 (공집합이라 미생성)", ":0\r\n", one(port, req("EXISTS", "d2")));

            // ④ SREM -> 빈 집합 자동 삭제
            check("④ SREM s a b -> :2", ":2\r\n", one(port, req("SREM", "s", "a", "b")));
            check("④ EXISTS s -> :0", ":0\r\n", one(port, req("EXISTS", "s")));

            // SPOP
            one(port, req("SADD", "sp", "x", "y", "z"));
            checkTrue("SPOP sp -> {x|y|z} 중 하나",
                    Set.of("x", "y", "z").contains(bulk(one(port, req("SPOP", "sp")))), "");
            check("SPOP 후 SCARD sp -> :2", ":2\r\n", one(port, req("SCARD", "sp")));
            String popped = one(port, req("SPOP", "sp", "5"));
            checkTrue("SPOP sp 5 -> 남은 2개", parseSet(popped).size() == 2, popped);
            check("SPOP 전부 후 EXISTS sp -> :0", ":0\r\n", one(port, req("EXISTS", "sp")));

            // SRANDMEMBER (비파괴)
            one(port, req("SADD", "sr", "a", "b", "c"));
            checkTrue("SRANDMEMBER sr -> 원소 하나",
                    Set.of("a", "b", "c").contains(bulk(one(port, req("SRANDMEMBER", "sr")))), "");
            checkTrue("SRANDMEMBER sr 2 -> 서로 다른 2개",
                    parseSet(one(port, req("SRANDMEMBER", "sr", "2"))).size() == 2, "");
            checkTrue("SRANDMEMBER sr -5 -> 5개(중복 허용)",
                    countBulks(one(port, req("SRANDMEMBER", "sr", "-5"))) == 5, "");
            check("SRANDMEMBER 비파괴: SCARD sr -> :3", ":3\r\n", one(port, req("SCARD", "sr")));

            // SMOVE
            one(port, req("SADD", "ma", "x", "y"));
            one(port, req("SADD", "mb", "z"));
            check("SMOVE ma mb x -> :1", ":1\r\n", one(port, req("SMOVE", "ma", "mb", "x")));
            check("SISMEMBER mb x -> :1", ":1\r\n", one(port, req("SISMEMBER", "mb", "x")));
            check("SISMEMBER ma x -> :0", ":0\r\n", one(port, req("SISMEMBER", "ma", "x")));
            check("SMOVE ma mb nope -> :0", ":0\r\n", one(port, req("SMOVE", "ma", "mb", "nope")));

            // WRONGTYPE 양방향
            one(port, req("SET", "str", "v"));
            checkTrue("SADD(string key) -> WRONGTYPE",
                    one(port, req("SADD", "str", "x")).startsWith("-WRONGTYPE"), "");
            checkTrue("GET(set key) -> WRONGTYPE",
                    one(port, req("GET", "s1")).startsWith("-WRONGTYPE"), "");
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

    private static String one(int port, byte[] request) throws IOException {
        try (Client c = new Client(port)) {
            return c.send(request);
        }
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

    /** 벌크 배열 와이어에서 원소 내용을 Set 으로 파싱(ASCII 원소 가정). */
    private static Set<String> parseSet(String wire) {
        Set<String> out = new HashSet<>();
        int i = wire.indexOf("\r\n") + 2;                    // "*N\r\n" 건너뜀
        while (i < wire.length() && wire.charAt(i) == '$') {
            int nl = wire.indexOf("\r\n", i);
            int len = Integer.parseInt(wire.substring(i + 1, nl));
            int start = nl + 2;
            if (len < 0) {
                i = start;
                continue;
            }
            out.add(wire.substring(start, start + len));
            i = start + len + 2;
        }
        return out;
    }

    private static int countBulks(String wire) {
        int count = 0;
        int i = wire.indexOf("\r\n") + 2;
        while (i < wire.length() && wire.charAt(i) == '$') {
            int nl = wire.indexOf("\r\n", i);
            int len = Integer.parseInt(wire.substring(i + 1, nl));
            int start = nl + 2;
            count++;
            i = start + Math.max(len, 0) + 2;
        }
        return count;
    }

    private static String bulk(String wire) {                // "$len\r\ncontent\r\n"
        int nl = wire.indexOf("\r\n");
        int len = Integer.parseInt(wire.substring(1, nl));
        return len < 0 ? null : wire.substring(nl + 2, nl + 2 + len);
    }

    private static void checkSet(String label, String wire, String... expected) {
        Set<String> got = parseSet(wire);
        Set<String> exp = new HashSet<>(Set.of(expected));
        if (got.equals(exp)) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + exp + " got=" + got);
        }
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
