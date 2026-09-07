import redis4j.server.RedisServer;

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
 * JDK 전용 검증 하니스 (JUnit/gradle/redis-cli 불필요).
 * REQ-NET-FUNC-01 [검증 기준] ①②③④ 를 실제 소켓으로 판정하고 PASS/FAIL 을 출력한다.
 * 컴파일: javac -d out (redis4j 메인 소스) Verify.java
 * 실행:   java -cp out Verify
 */
public final class Verify {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify: redis4j on port " + port);
        try {
            check("① PING -> +PONG", "+PONG\r\n", oneShot(port, "*1\r\n$4\r\nPING\r\n"));
            check("③ ECHO hi -> $2 hi", "$2\r\nhi\r\n", oneShot(port, "*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n"));

            try (Client c = new Client(port)) {             // ④ 미지원 명령 + 연결 유지
                String err = c.send("*1\r\n$3\r\nFOO\r\n");
                checkTrue("④ unknown -> -ERR unknown command", err.startsWith("-ERR unknown command"), err);
                check("④ 연결 유지 후 PING", "+PONG\r\n", c.send("*1\r\n$4\r\nPING\r\n"));
            }

            checkTrue("② 동시 50 클라이언트 각각 +PONG", concurrent(port, 50), "일부 응답 불일치");
            check("(부가) inline PING", "+PONG\r\n", oneShot(port, "PING\r\n"));
            check("(부가) PING msg -> bulk", "$5\r\nhello\r\n", oneShot(port, "*2\r\n$4\r\nPING\r\n$5\r\nhello\r\n"));
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

    private static boolean concurrent(int port, int n) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> oneShot(port, "*1\r\n$4\r\nPING\r\n")));
            }
            boolean ok = true;
            for (Future<String> f : futures) {
                if (!"+PONG\r\n".equals(f.get(5, TimeUnit.SECONDS))) {
                    ok = false;
                }
            }
            return ok;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String oneShot(int port, String raw) throws IOException {
        try (Client c = new Client(port)) {
            return c.send(raw);
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

        String send(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readReply();
        }

        private String readReply() throws IOException {
            int type = in.read();
            if (type == -1) {
                return "";
            }
            String head = ((char) type) + readLine();
            char t = (char) type;
            if (t == '+' || t == '-' || t == ':') {
                return head + "\r\n";
            }
            if (t == '$') {
                int len = Integer.parseInt(head.substring(1).trim());
                if (len < 0) {
                    return head + "\r\n";
                }
                byte[] body = in.readNBytes(len);
                in.read();
                in.read();
                return head + "\r\n" + new String(body, StandardCharsets.UTF_8) + "\r\n";
            }
            return head + "\r\n";
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
