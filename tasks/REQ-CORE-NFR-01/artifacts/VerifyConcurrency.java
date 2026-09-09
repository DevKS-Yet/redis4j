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
 * REQ-CORE-NFR-01 JDK 전용 검증 하니스(gradle 미설치 대응). 실소켓 왕복으로
 * ①동일 키 동시 갱신 일관 ②INCR 동시 N=정확히 N(원자성) ③혼합 부하 무데드락·무손상
 * + 요구 #5 연결 상한(초과 거부·정리 후 재수용) 을 증명한다.
 */
public final class VerifyConcurrency {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        try (RedisServer server = new RedisServer(0)) {
            int port = server.start();
            consistentSameKey(port);        // ①
            incrAtomic(port);               // ②
            mixedNoDeadlock(port);          // ③
            connectionLimit(server, port);  // #5
        }
        System.out.println("---");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ① 동일 키에 50 클라 × 200 고유 SADD → 손실 없으면 SCARD == 10000
    private static void consistentSameKey(int port) throws Exception {
        int clients = 50, perClient = 200, total = clients * perClient;
        runConcurrently(clients, id -> {
            try (Client c = new Client(port)) {
                for (int i = 0; i < perClient; i++) {
                    c.send(cmd("SADD", "s", "m-" + id + "-" + i));
                }
            }
        });
        try (Client c = new Client(port)) {
            check("① 동일 키 동시 SADD 일관 SCARD -> " + total,
                    (":" + total + "\r\n").equals(c.send(cmd("SCARD", "s"))));
        }
    }

    // ② 50 클라 × 1000 INCR → GET n == 50000 (원자성)
    private static void incrAtomic(int port) throws Exception {
        int clients = 50, perClient = 1000, expected = clients * perClient;
        runConcurrently(clients, id -> {
            try (Client c = new Client(port)) {
                for (int i = 0; i < perClient; i++) {
                    c.send(cmd("INCR", "n"));
                }
            }
        });
        try (Client c = new Client(port)) {
            check("② INCR 동시 " + expected + "회 = 정확히 " + expected,   // GET 은 벌크 스트링
                    bulk(String.valueOf(expected)).equals(c.send(cmd("GET", "n"))));
        }
    }

    // ③ 40 클라 × 500 혼합(SET/GET/INCR/RPUSH) → 완료(무데드락) + 불변식(무손상)
    private static void mixedNoDeadlock(int port) throws Exception {
        int clients = 40, perClient = 500;
        long start = System.currentTimeMillis();
        runConcurrently(clients, id -> {
            try (Client c = new Client(port)) {
                for (int i = 0; i < perClient; i++) {
                    c.send(cmd("SET", "k-" + id, "v" + i));
                    c.send(cmd("GET", "k-" + id));
                    c.send(cmd("INCR", "counter"));
                    c.send(cmd("RPUSH", "log", id + ":" + i));
                }
            }
        });
        long elapsed = System.currentTimeMillis() - start;
        check("③ 혼합 부하 완료(무데드락) " + elapsed + "ms", elapsed < 30000);
        try (Client c = new Client(port)) {
            int n = clients * perClient;
            check("③ INCR 불변식 counter == " + n,                          // GET 은 벌크 스트링
                    bulk(String.valueOf(n)).equals(c.send(cmd("GET", "counter"))));
            check("③ RPUSH 불변식 LLEN log == " + n,                        // LLEN 은 정수
                    (":" + n + "\r\n").equals(c.send(cmd("LLEN", "log"))));
        }
    }

    // #5 상한 3 → 3개 정상, 4번째 거부, 정리 후 카운트 0 복귀 + 재수용
    private static void connectionLimit(RedisServer server, int port) throws Exception {
        server.setMaxClients(3);
        List<Client> held = new ArrayList<>();
        boolean allOk = true;
        for (int i = 0; i < 3; i++) {
            Client c = new Client(port);
            allOk &= "+PONG\r\n".equals(c.send(cmd("PING")));
            held.add(c);
        }
        check("#5 상한 이내 3연결 정상", allOk);
        try (Client over = new Client(port)) {
            check("#5 상한 초과 거부(-ERR max number of clients reached)",
                    over.send(cmd("PING")).startsWith("-ERR max number of clients reached"));
        }
        for (Client c : held) {
            c.close();
        }
        long deadline = System.currentTimeMillis() + 5000;
        while (server.activeClients() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        check("#5 연결 정리 후 activeClients == 0", server.activeClients() == 0);
        try (Client again = new Client(port)) {
            check("#5 정리 후 재수용 PING -> PONG", "+PONG\r\n".equals(again.send(cmd("PING"))));
        }
        server.setMaxClients(10_000);
    }

    private interface ClientTask {
        void run(int id) throws Exception;
    }

    private static void runConcurrently(int n, ClientTask task) throws Exception {
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

    private static void check(String label, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label);
        }
    }

    /** 벌크 스트링 응답 와이어 형식($len\r\n payload \r\n). */
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
