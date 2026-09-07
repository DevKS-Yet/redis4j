package redis4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-NET-FUNC-01 [검증 기준] ①②③④ 를 실제 소켓 왕복으로 검증. */
class ServerIntegrationTest {

    private RedisServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = new RedisServer(0);           // 임의 포트
        port = server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void ping_returns_pong() throws IOException {                     // ①
        try (Client c = new Client(port)) {
            assertEquals("+PONG\r\n", c.send("*1\r\n$4\r\nPING\r\n"));
        }
    }

    @Test
    void echo_returns_argument() throws IOException {                 // ③
        try (Client c = new Client(port)) {
            assertEquals("$2\r\nhi\r\n", c.send("*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n"));
        }
    }

    @Test
    void unknown_command_errors_but_keeps_connection() throws IOException {   // ④
        try (Client c = new Client(port)) {
            String err = c.send("*1\r\n$3\r\nFOO\r\n");
            assertTrue(err.startsWith("-ERR unknown command"), err);
            assertEquals("+PONG\r\n", c.send("*1\r\n$4\r\nPING\r\n"));
        }
    }

    @Test
    void handles_concurrent_clients() throws Exception {             // ②
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    try (Client c = new Client(port)) {
                        return c.send("*1\r\n$4\r\nPING\r\n");
                    }
                }));
            }
            for (Future<String> f : futures) {
                assertEquals("+PONG\r\n", f.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 요청을 보내고 응답 한 개를 와이어 문자열로 읽는 최소 클라이언트. */
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
