import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** REQ-HASH-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①②③④ + 전체 Hash 명령. */
public final class VerifyHash {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-hash: redis4j on port " + port);
        try {
            // ① HSET + HGET
            check("① HSET h f1 v1 f2 v2 -> :2", ":2\r\n", one(port, req("HSET", "h", "f1", "v1", "f2", "v2")));
            check("① HGET h f1 -> v1", "$2\r\nv1\r\n", one(port, req("HGET", "h", "f1")));

            // ② HGETALL (삽입 순서)
            check("② HGETALL h -> f1 v1 f2 v2",
                    "*4\r\n$2\r\nf1\r\n$2\r\nv1\r\n$2\r\nf2\r\n$2\r\nv2\r\n", one(port, req("HGETALL", "h")));

            // 갱신은 신규 0
            check("HSET h f1 X (갱신) -> :0", ":0\r\n", one(port, req("HSET", "h", "f1", "X")));
            check("HGET h f1 -> X", "$1\r\nX\r\n", one(port, req("HGET", "h", "f1")));
            check("HLEN h -> :2", ":2\r\n", one(port, req("HLEN", "h")));
            check("HKEYS h -> [f1,f2]", "*2\r\n$2\r\nf1\r\n$2\r\nf2\r\n", one(port, req("HKEYS", "h")));
            check("HVALS h -> [X,v2]", "*2\r\n$1\r\nX\r\n$2\r\nv2\r\n", one(port, req("HVALS", "h")));
            check("HEXISTS h f1 -> :1", ":1\r\n", one(port, req("HEXISTS", "h", "f1")));
            check("HEXISTS h nope -> :0", ":0\r\n", one(port, req("HEXISTS", "h", "nope")));
            check("HMGET h f1 f2 nope -> [X,v2,nil]",
                    "*3\r\n$1\r\nX\r\n$2\r\nv2\r\n$-1\r\n", one(port, req("HMGET", "h", "f1", "f2", "nope")));

            // ③ HDEL + HEXISTS
            check("③ HDEL h f1 -> :1", ":1\r\n", one(port, req("HDEL", "h", "f1")));
            check("③ HEXISTS h f1 -> :0", ":0\r\n", one(port, req("HEXISTS", "h", "f1")));
            check("HLEN h -> :1", ":1\r\n", one(port, req("HLEN", "h")));

            // ④ HINCRBY
            check("④ HINCRBY hc cnt 5 -> :5", ":5\r\n", one(port, req("HINCRBY", "hc", "cnt", "5")));
            check("HINCRBY hc cnt 3 -> :8", ":8\r\n", one(port, req("HINCRBY", "hc", "cnt", "3")));
            one(port, req("HSET", "hc", "s", "abc"));
            checkTrue("HINCRBY hc s 1 -> 비정수 에러",
                    one(port, req("HINCRBY", "hc", "s", "1")).startsWith("-ERR hash value is not an integer"),
                    one(port, req("HINCRBY", "hc", "s", "1")));

            // HINCRBYFLOAT
            check("HINCRBYFLOAT hf x 1.5 -> 1.5", "$3\r\n1.5\r\n", one(port, req("HINCRBYFLOAT", "hf", "x", "1.5")));
            check("HINCRBYFLOAT hf x 2.5 -> 4", "$1\r\n4\r\n", one(port, req("HINCRBYFLOAT", "hf", "x", "2.5")));

            // HSTRLEN
            one(port, req("HSET", "hs", "f", "hello"));
            check("HSTRLEN hs f -> :5", ":5\r\n", one(port, req("HSTRLEN", "hs", "f")));
            check("HSTRLEN hs nope -> :0", ":0\r\n", one(port, req("HSTRLEN", "hs", "nope")));

            // HSETNX
            check("HSETNX hn a 1 -> :1", ":1\r\n", one(port, req("HSETNX", "hn", "a", "1")));
            check("HSETNX hn a 2 -> :0", ":0\r\n", one(port, req("HSETNX", "hn", "a", "2")));
            check("HGET hn a -> 1", "$1\r\n1\r\n", one(port, req("HGET", "hn", "a")));

            // HMSET
            check("HMSET hm a 1 b 2 -> +OK", "+OK\r\n", one(port, req("HMSET", "hm", "a", "1", "b", "2")));
            check("HGETALL hm -> a 1 b 2",
                    "*4\r\n$1\r\na\r\n$1\r\n1\r\n$1\r\nb\r\n$1\r\n2\r\n", one(port, req("HGETALL", "hm")));

            // TYPE / WRONGTYPE 양방향
            check("TYPE h -> +hash", "+hash\r\n", one(port, req("TYPE", "h")));
            one(port, req("SET", "str", "v"));
            checkTrue("HSET(string key) -> WRONGTYPE",
                    one(port, req("HSET", "str", "f", "v")).startsWith("-WRONGTYPE"),
                    one(port, req("HSET", "str", "f", "v")));
            checkTrue("GET(hash key) -> WRONGTYPE",
                    one(port, req("GET", "h")).startsWith("-WRONGTYPE"), one(port, req("GET", "h")));

            // 빈 해시 자동 삭제
            one(port, req("HSET", "he", "only", "1"));
            check("HDEL he only -> :1", ":1\r\n", one(port, req("HDEL", "he", "only")));
            check("EXISTS he -> :0 (자동 삭제)", ":0\r\n", one(port, req("EXISTS", "he")));
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
