import redis4j.server.RedisServer;
import redis4j.store.RedisType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * REQ-STR-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①②③④ + 추가 동작을 실제 소켓으로 판정.
 * 완료조건 ③(WRONGTYPE)은 타입 생성 명령이 없으므로 비-STRING 값을 Database 에 주입해 검증.
 */
public final class VerifyStr {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-str: redis4j on port " + port);
        try {
            // ① SET/GET
            check("① SET foo bar -> +OK", "+OK\r\n", one(port, req("SET", "foo", "bar")));
            check("① GET foo -> bar", "$3\r\nbar\r\n", one(port, req("GET", "foo")));

            // ② INCR
            check("② SET n 10 -> +OK", "+OK\r\n", one(port, req("SET", "n", "10")));
            check("② INCR n -> :11", ":11\r\n", one(port, req("INCR", "n")));
            check("  DECRBY n 5 -> :6", ":6\r\n", one(port, req("DECRBY", "n", "5")));
            check("  SET s abc; INCR s -> NOT_INT", "+OK\r\n", one(port, req("SET", "s", "abc")));
            checkTrue("  INCR s -> -ERR not integer",
                    one(port, req("INCR", "s")).startsWith("-ERR value is not an integer"),
                    one(port, req("INCR", "s")));

            // ③ WRONGTYPE (비-STRING 주입)
            synchronized (server.database()) {
                server.database().put("mylist", () -> RedisType.LIST, 0L);
            }
            checkTrue("③ GET(list key) -> -WRONGTYPE",
                    one(port, req("GET", "mylist")).startsWith("-WRONGTYPE"),
                    one(port, req("GET", "mylist")));
            check("③ TYPE mylist -> +list", "+list\r\n", one(port, req("TYPE", "mylist")));

            // ④ 만료 (SET PX + lazy)
            check("④ SET k v PX 100 -> +OK", "+OK\r\n", one(port, req("SET", "k", "v", "PX", "100")));
            check("④ GET k (즉시) -> v", "$1\r\nv\r\n", one(port, req("GET", "k")));
            Thread.sleep(160);
            check("④ GET k (만료 후) -> nil", "$-1\r\n", one(port, req("GET", "k")));

            // APPEND / STRLEN
            one(port, req("DEL", "a"));
            check("APPEND a Hello -> :5", ":5\r\n", one(port, req("APPEND", "a", "Hello")));
            check("APPEND a ' World' -> :11", ":11\r\n", one(port, req("APPEND", "a", " World")));
            check("GET a -> 'Hello World'", "$11\r\nHello World\r\n", one(port, req("GET", "a")));
            check("STRLEN a -> :11", ":11\r\n", one(port, req("STRLEN", "a")));

            // GETSET / SETNX
            one(port, req("DEL", "g"));
            check("GETSET g v1 (없음) -> nil", "$-1\r\n", one(port, req("GETSET", "g", "v1")));
            check("GETSET g v2 -> v1", "$2\r\nv1\r\n", one(port, req("GETSET", "g", "v2")));
            one(port, req("DEL", "sx"));
            check("SETNX sx a -> :1", ":1\r\n", one(port, req("SETNX", "sx", "a")));
            check("SETNX sx b -> :0", ":0\r\n", one(port, req("SETNX", "sx", "b")));

            // MSET / MGET
            check("MSET x 1 y 2 -> +OK", "+OK\r\n", one(port, req("MSET", "x", "1", "y", "2")));
            check("MGET x y nope -> [1,2,nil]",
                    "*3\r\n$1\r\n1\r\n$1\r\n2\r\n$-1\r\n", one(port, req("MGET", "x", "y", "nope")));

            // TYPE / DEL multi / EXISTS
            check("TYPE foo -> +string", "+string\r\n", one(port, req("TYPE", "foo")));
            check("TYPE nope -> +none", "+none\r\n", one(port, req("TYPE", "nope")));
            check("DEL x y -> :2", ":2\r\n", one(port, req("DEL", "x", "y")));
            check("EXISTS x foo -> :1", ":1\r\n", one(port, req("EXISTS", "x", "foo")));

            // 바이트 안전 (값에 개행 포함)
            check("SET b 'a\\nb' -> +OK", "+OK\r\n", one(port, req("SET", "b", "a\nb")));
            check("GET b -> 'a\\nb' (3 bytes)", "$3\r\na\nb\r\n", one(port, req("GET", "b")));

            // 동시 INCR 원자성
            one(port, req("DEL", "c"));
            int n = 50;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> one(port, req("INCR", "c"))));
            }
            boolean allInt = true;
            for (Future<String> f : fs) {
                if (!f.get(5, TimeUnit.SECONDS).startsWith(":")) {
                    allInt = false;
                }
            }
            pool.shutdownNow();
            checkTrue("동시 INCR 50회 각각 정수 응답", allInt, "일부 비정수");
            check("최종 GET c -> :50? (실제 GET)", "$2\r\n50\r\n", one(port, req("GET", "c")));
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

    /** 문자열 인자들로 RESP2 Bulk String 배열 요청을 만든다(길이는 바이트 기준). */
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

        /** RESP2 값 하나를 와이어 문자열로 복원(중첩 배열 포함). */
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
