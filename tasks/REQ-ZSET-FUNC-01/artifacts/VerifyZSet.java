import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** REQ-ZSET-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①②③④ + 정렬/범위/플래그. 순서가 중요하므로 정확 비교. */
public final class VerifyZSet {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-zset: redis4j on port " + port);
        try {
            // ① ZADD + ZRANGE
            check("① ZADD z 1 a 2 b 3 c -> :3", ":3\r\n", one(port, req("ZADD", "z", "1", "a", "2", "b", "3", "c")));
            check("① ZRANGE z 0 -1 -> [a,b,c]", arr("a", "b", "c"), one(port, req("ZRANGE", "z", "0", "-1")));
            check("ZRANGE WITHSCORES -> [a,1,b,2,c,3]", arr("a", "1", "b", "2", "c", "3"),
                    one(port, req("ZRANGE", "z", "0", "-1", "WITHSCORES")));

            // ② ZRANGEBYSCORE
            check("② ZRANGEBYSCORE z 2 3 -> [b,c]", arr("b", "c"), one(port, req("ZRANGEBYSCORE", "z", "2", "3")));
            check("ZRANGEBYSCORE z (1 3 -> [b,c]", arr("b", "c"), one(port, req("ZRANGEBYSCORE", "z", "(1", "3")));
            check("ZRANGEBYSCORE z -inf +inf -> [a,b,c]", arr("a", "b", "c"),
                    one(port, req("ZRANGEBYSCORE", "z", "-inf", "+inf")));

            // ③ ZRANK
            check("③ ZRANK z b -> :1", ":1\r\n", one(port, req("ZRANK", "z", "b")));
            check("ZREVRANK z a -> :2", ":2\r\n", one(port, req("ZREVRANK", "z", "a")));
            check("ZRANK z nope -> nil", "$-1\r\n", one(port, req("ZRANK", "z", "nope")));

            // ZSCORE/ZCARD/ZCOUNT
            check("ZSCORE z b -> 2", blk("2"), one(port, req("ZSCORE", "z", "b")));
            check("ZSCORE z nope -> nil", "$-1\r\n", one(port, req("ZSCORE", "z", "nope")));
            check("ZCARD z -> :3", ":3\r\n", one(port, req("ZCARD", "z")));
            check("ZCOUNT z 1 2 -> :2", ":2\r\n", one(port, req("ZCOUNT", "z", "1", "2")));
            check("ZCOUNT z (1 +inf -> :2", ":2\r\n", one(port, req("ZCOUNT", "z", "(1", "+inf")));

            // ④ ZINCRBY + ZSCORE
            check("④ ZINCRBY z 5 a -> 6", blk("6"), one(port, req("ZINCRBY", "z", "5", "a")));
            check("④ ZSCORE z a -> 6", blk("6"), one(port, req("ZSCORE", "z", "a")));
            check("ZINCRBY 후 ZRANGE z 0 -1 -> [b,c,a]", arr("b", "c", "a"), one(port, req("ZRANGE", "z", "0", "-1")));
            check("ZREVRANGE z 0 -1 -> [a,c,b]", arr("a", "c", "b"), one(port, req("ZREVRANGE", "z", "0", "-1")));
            check("ZMSCORE z a b nope -> [6,2,nil]",
                    "*3\r\n$1\r\n6\r\n$1\r\n2\r\n$-1\r\n", one(port, req("ZMSCORE", "z", "a", "b", "nope")));

            // ZADD 플래그
            check("ZADD f XX 1 new -> :0", ":0\r\n", one(port, req("ZADD", "f", "XX", "1", "new")));
            check("ZSCORE f new -> nil", "$-1\r\n", one(port, req("ZSCORE", "f", "new")));
            check("ZADD f NX 1 a -> :1", ":1\r\n", one(port, req("ZADD", "f", "NX", "1", "a")));
            check("ZADD f NX 2 a -> :0", ":0\r\n", one(port, req("ZADD", "f", "NX", "2", "a")));
            check("ZSCORE f a -> 1 (NX 유지)", blk("1"), one(port, req("ZSCORE", "f", "a")));
            check("ZADD f GT 5 a -> :0", ":0\r\n", one(port, req("ZADD", "f", "GT", "5", "a")));
            check("ZSCORE f a -> 5 (GT 갱신)", blk("5"), one(port, req("ZSCORE", "f", "a")));
            check("ZADD f GT 3 a -> :0", ":0\r\n", one(port, req("ZADD", "f", "GT", "3", "a")));
            check("ZSCORE f a -> 5 (GT 미갱신)", blk("5"), one(port, req("ZSCORE", "f", "a")));
            check("ZADD f CH 10 a -> :1 (변경 카운트)", ":1\r\n", one(port, req("ZADD", "f", "CH", "10", "a")));
            check("ZADD f INCR 5 a -> 15", blk("15"), one(port, req("ZADD", "f", "INCR", "5", "a")));
            check("ZADD f NX INCR 1 a -> nil", "$-1\r\n", one(port, req("ZADD", "f", "NX", "INCR", "1", "a")));

            // ZRANGEBYLEX
            one(port, req("ZADD", "lex", "0", "a", "0", "b", "0", "c", "0", "d"));
            check("ZRANGEBYLEX lex - + -> [a,b,c,d]", arr("a", "b", "c", "d"), one(port, req("ZRANGEBYLEX", "lex", "-", "+")));
            check("ZRANGEBYLEX lex [b (d -> [b,c]", arr("b", "c"), one(port, req("ZRANGEBYLEX", "lex", "[b", "(d")));

            // ZREMRANGEBYRANK / BYSCORE
            one(port, req("ZADD", "rr", "1", "a", "2", "b", "3", "c", "4", "d"));
            check("ZREMRANGEBYRANK rr 0 1 -> :2", ":2\r\n", one(port, req("ZREMRANGEBYRANK", "rr", "0", "1")));
            check("ZRANGE rr 0 -1 -> [c,d]", arr("c", "d"), one(port, req("ZRANGE", "rr", "0", "-1")));
            one(port, req("ZADD", "rs", "1", "a", "2", "b", "3", "c"));
            check("ZREMRANGEBYSCORE rs 2 3 -> :2", ":2\r\n", one(port, req("ZREMRANGEBYSCORE", "rs", "2", "3")));
            check("ZRANGE rs 0 -1 -> [a]", arr("a"), one(port, req("ZRANGE", "rs", "0", "-1")));

            // ZREM + 빈 zset 자동 삭제
            one(port, req("ZADD", "re", "1", "a"));
            check("ZREM re a -> :1", ":1\r\n", one(port, req("ZREM", "re", "a")));
            check("EXISTS re -> :0 (자동 삭제)", ":0\r\n", one(port, req("EXISTS", "re")));

            // WRONGTYPE 양방향
            one(port, req("SET", "str", "v"));
            checkTrue("ZADD(string key) -> WRONGTYPE",
                    one(port, req("ZADD", "str", "1", "a")).startsWith("-WRONGTYPE"), "");
            checkTrue("GET(zset key) -> WRONGTYPE", one(port, req("GET", "z")).startsWith("-WRONGTYPE"), "");
            check("TYPE z -> +zset", "+zset\r\n", one(port, req("TYPE", "z")));
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

    /** 기대 배열 와이어 생성(ASCII 원소). */
    private static String arr(String... items) {
        StringBuilder sb = new StringBuilder("*").append(items.length).append("\r\n");
        for (String it : items) {
            byte[] b = it.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(it).append("\r\n");
        }
        return sb.toString();
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
