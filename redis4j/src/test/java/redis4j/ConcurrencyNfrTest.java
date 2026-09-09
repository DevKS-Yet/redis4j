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
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static java.time.Duration.ofSeconds;

/**
 * REQ-CORE-NFR-01 [검증 기준] ①②③ 를 실제 소켓 왕복으로 검증한다(+ 요구 #5 연결 상한).
 * 동시성 모델(전역 단일 모니터 + VT-per-connection)의 원자성·일관성·무데드락을 증명한다.
 */
class ConcurrencyNfrTest {

    private RedisServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = new RedisServer(0);
        port = server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void consistentUnderConcurrentSameKeyUpdates() throws Exception {   // ①
        int clients = 50, perClient = 200, total = clients * perClient;
        runConcurrently(clients, id -> {
            try (Client c = new Client(port)) {
                for (int i = 0; i < perClient; i++) {
                    c.send(cmd("SADD", "s", "m-" + id + "-" + i));       // 고유 멤버 → 손실 없으면 total 개
                }
            }
        });
        try (Client c = new Client(port)) {
            assertEquals(":" + total + "\r\n", c.send(cmd("SCARD", "s")));  // 동일 키 동시 갱신 → 최종 일관(무손실)
        }
    }

    @Test
    void concurrentIncrIsAtomic() throws Exception {                   // ②
        int clients = 50, perClient = 1000, expected = clients * perClient;
        runConcurrently(clients, id -> {
            try (Client c = new Client(port)) {
                for (int i = 0; i < perClient; i++) {
                    c.send(cmd("INCR", "n"));
                }
            }
        });
        try (Client c = new Client(port)) {                                 // GET 은 벌크 스트링
            assertEquals(bulk(String.valueOf(expected)), c.send(cmd("GET", "n")));  // 정확히 N 증가(원자성)
        }
    }

    @Test
    void mixedLoadNoDeadlockNoCorruption() throws Exception {          // ③
        int clients = 40, perClient = 500;
        assertTimeout(ofSeconds(30), () ->                             // 완료 = 데드락 없음
            runConcurrently(clients, id -> {
                try (Client c = new Client(port)) {
                    for (int i = 0; i < perClient; i++) {
                        c.send(cmd("SET", "k-" + id, "v" + i));
                        c.send(cmd("GET", "k-" + id));
                        c.send(cmd("INCR", "counter"));
                        c.send(cmd("RPUSH", "log", id + ":" + i));
                    }
                }
            }));
        try (Client c = new Client(port)) {                            // 불변식: 손상 없음
            assertEquals(bulk(String.valueOf(clients * perClient)), c.send(cmd("GET", "counter")));  // GET=벌크
            assertEquals(":" + (clients * perClient) + "\r\n", c.send(cmd("LLEN", "log")));           // LLEN=정수
        }
    }

    @Test
    void connectionLimitRejectsExcessAndCleansUp() throws Exception {  // 요구 #5
        server.setMaxClients(3);
        List<Client> held = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Client c = new Client(port);
            assertEquals("+PONG\r\n", c.send(cmd("PING")));            // 상한 이내는 정상
            held.add(c);
        }
        try (Client over = new Client(port)) {
            assertTrue(over.send(cmd("PING")).startsWith("-ERR max number of clients reached"));  // 초과 거부
        }
        for (Client c : held) {
            c.close();
        }
        long deadline = System.currentTimeMillis() + 5000;            // 정리 후 카운트 0 복귀
        while (server.activeClients() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, server.activeClients());
        try (Client again = new Client(port)) {
            assertEquals("+PONG\r\n", again.send(cmd("PING")));        // 정리 후 재수용
        }
    }

    private interface ClientTask {
        void run(int id) throws Exception;
    }

    private void runConcurrently(int n, ClientTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int id = i;
                futures.add(pool.submit(() -> {
                    task.run(id);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static String bulk(String s) {
        return "$" + s.getBytes(StandardCharsets.UTF_8).length + "\r\n" + s + "\r\n";
    }

    private static String cmd(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
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
