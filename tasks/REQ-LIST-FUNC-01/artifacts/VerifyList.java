import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** REQ-LIST-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①②③④ + 전체 List 명령 + STR③ 종단 재확인. */
public final class VerifyList {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-list: redis4j on port " + port);
        try {
            // ① RPUSH + LRANGE
            check("① RPUSH l a b c -> :3", ":3\r\n", one(port, req("RPUSH", "l", "a", "b", "c")));
            check("① LRANGE l 0 -1 -> [a,b,c]",
                    "*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", one(port, req("LRANGE", "l", "0", "-1")));

            // ② LPOP + LLEN
            check("② LPOP l -> a", "$1\r\na\r\n", one(port, req("LPOP", "l")));
            check("② LLEN l -> :2", ":2\r\n", one(port, req("LLEN", "l")));

            // ③ 전부 제거 -> EXISTS 0 (자동 삭제)
            check("③ RPOP l -> c", "$1\r\nc\r\n", one(port, req("RPOP", "l")));
            check("③ RPOP l -> b", "$1\r\nb\r\n", one(port, req("RPOP", "l")));
            check("③ EXISTS l -> :0", ":0\r\n", one(port, req("EXISTS", "l")));

            // ④ WRONGTYPE 양방향
            check("  SET s v -> +OK", "+OK\r\n", one(port, req("SET", "s", "v")));
            checkTrue("④ LPUSH(string key) -> WRONGTYPE",
                    one(port, req("LPUSH", "s", "x")).startsWith("-WRONGTYPE"),
                    one(port, req("LPUSH", "s", "x")));
            one(port, req("RPUSH", "ll", "a"));
            checkTrue("STR③ 종단: GET(list key) -> WRONGTYPE",
                    one(port, req("GET", "ll")).startsWith("-WRONGTYPE"),
                    one(port, req("GET", "ll")));

            // LPUSH 순서
            check("LPUSH m a b c -> :3", ":3\r\n", one(port, req("LPUSH", "m", "a", "b", "c")));
            check("LRANGE m 0 -1 -> [c,b,a]",
                    "*3\r\n$1\r\nc\r\n$1\r\nb\r\n$1\r\na\r\n", one(port, req("LRANGE", "m", "0", "-1")));

            // LINDEX / LSET
            check("LINDEX m 0 -> c", "$1\r\nc\r\n", one(port, req("LINDEX", "m", "0")));
            check("LINDEX m -1 -> a", "$1\r\na\r\n", one(port, req("LINDEX", "m", "-1")));
            check("LINDEX m 5 -> nil", "$-1\r\n", one(port, req("LINDEX", "m", "5")));
            check("LSET m 0 X -> +OK", "+OK\r\n", one(port, req("LSET", "m", "0", "X")));
            check("LINDEX m 0 -> X", "$1\r\nX\r\n", one(port, req("LINDEX", "m", "0")));

            // LREM
            one(port, req("DEL", "r"));
            check("RPUSH r a b a c a -> :5", ":5\r\n", one(port, req("RPUSH", "r", "a", "b", "a", "c", "a")));
            check("LREM r 2 a -> :2", ":2\r\n", one(port, req("LREM", "r", "2", "a")));
            check("LRANGE r 0 -1 -> [b,c,a]",
                    "*3\r\n$1\r\nb\r\n$1\r\nc\r\n$1\r\na\r\n", one(port, req("LRANGE", "r", "0", "-1")));
            check("LREM r -1 a -> :1", ":1\r\n", one(port, req("LREM", "r", "-1", "a")));
            check("LRANGE r 0 -1 -> [b,c]",
                    "*2\r\n$1\r\nb\r\n$1\r\nc\r\n", one(port, req("LRANGE", "r", "0", "-1")));

            // LTRIM
            one(port, req("DEL", "t"));
            one(port, req("RPUSH", "t", "a", "b", "c", "d", "e"));
            check("LTRIM t 1 3 -> +OK", "+OK\r\n", one(port, req("LTRIM", "t", "1", "3")));
            check("LRANGE t 0 -1 -> [b,c,d]",
                    "*3\r\n$1\r\nb\r\n$1\r\nc\r\n$1\r\nd\r\n", one(port, req("LRANGE", "t", "0", "-1")));

            // LINSERT
            one(port, req("DEL", "i"));
            one(port, req("RPUSH", "i", "a", "c"));
            check("LINSERT i BEFORE c b -> :3", ":3\r\n", one(port, req("LINSERT", "i", "BEFORE", "c", "b")));
            check("LRANGE i 0 -1 -> [a,b,c]",
                    "*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", one(port, req("LRANGE", "i", "0", "-1")));
            check("LINSERT i AFTER c d -> :4", ":4\r\n", one(port, req("LINSERT", "i", "AFTER", "c", "d")));
            check("LINSERT i BEFORE zzz x -> :-1", ":-1\r\n", one(port, req("LINSERT", "i", "BEFORE", "zzz", "x")));

            // LPUSHX / RPUSHX
            one(port, req("DEL", "x"));
            check("LPUSHX x a (없음) -> :0", ":0\r\n", one(port, req("LPUSHX", "x", "a")));
            check("RPUSH x a -> :1", ":1\r\n", one(port, req("RPUSH", "x", "a")));
            check("RPUSHX x b -> :2", ":2\r\n", one(port, req("RPUSHX", "x", "b")));
            check("LRANGE x 0 -1 -> [a,b]",
                    "*2\r\n$1\r\na\r\n$1\r\nb\r\n", one(port, req("LRANGE", "x", "0", "-1")));

            // LPOP count
            one(port, req("DEL", "cc"));
            one(port, req("RPUSH", "cc", "a", "b", "c", "d"));
            check("LPOP cc 2 -> [a,b]",
                    "*2\r\n$1\r\na\r\n$1\r\nb\r\n", one(port, req("LPOP", "cc", "2")));
            check("LRANGE cc 0 -1 -> [c,d]",
                    "*2\r\n$1\r\nc\r\n$1\r\nd\r\n", one(port, req("LRANGE", "cc", "0", "-1")));

            // LLEN 경계
            check("LLEN absent -> :0", ":0\r\n", one(port, req("LLEN", "absent")));
            checkTrue("LLEN(string key) -> WRONGTYPE",
                    one(port, req("LLEN", "s")).startsWith("-WRONGTYPE"), one(port, req("LLEN", "s")));
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
        return s.replace("\r", "\\r").replace("\n", "\\n");
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
