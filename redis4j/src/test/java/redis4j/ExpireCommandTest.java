package redis4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-EXP-FUNC-01 완료조건 ①②③④ + 확장 검증. */
class ExpireCommandTest {

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
    void pexpire_then_lazy_expires() throws IOException, InterruptedException {   // ①
        try (Client c = new Client(port)) {
            assertEquals("+OK\r\n", c.send(req("SET", "k", "v")));
            assertEquals(":1\r\n", c.send(req("PEXPIRE", "k", "100")));
            assertEquals("$1\r\nv\r\n", c.send(req("GET", "k")));
            Thread.sleep(160);
            assertEquals("$-1\r\n", c.send(req("GET", "k")));
        }
    }

    @Test
    void ttl_conventions() throws IOException {                                  // ②
        try (Client c = new Client(port)) {
            assertEquals(":-2\r\n", c.send(req("TTL", "absent")));
            c.send(req("SET", "p", "v"));
            assertEquals(":-1\r\n", c.send(req("TTL", "p")));
        }
    }

    @Test
    void persist_removes_expiry_keeps_key() throws IOException {                 // ③
        try (Client c = new Client(port)) {
            c.send(req("SET", "q", "v"));
            assertEquals(":1\r\n", c.send(req("EXPIRE", "q", "100")));
            assertEquals(":1\r\n", c.send(req("PERSIST", "q")));
            assertEquals(":-1\r\n", c.send(req("TTL", "q")));
            assertEquals("$1\r\nv\r\n", c.send(req("GET", "q")));
            assertEquals(":0\r\n", c.send(req("PERSIST", "q")));
        }
    }

    @Test
    void set_ex_reflected_in_ttl() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SET", "e", "v", "EX", "100"));
            long ttl = intVal(c.send(req("TTL", "e")));
            assertTrue(ttl >= 99 && ttl <= 100, "ttl=" + ttl);
        }
    }

    @Test
    void expireat_in_past_deletes() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SET", "z", "v"));
            assertEquals(":1\r\n", c.send(req("PEXPIREAT", "z", "1")));
            assertEquals("$-1\r\n", c.send(req("GET", "z")));
        }
    }

    @Test
    void active_expiration_without_access() throws Exception {                   // ④
        int baseline;
        synchronized (server.database()) {
            baseline = server.database().rawSize();
        }
        try (Client c = new Client(port)) {
            for (int i = 0; i < 200; i++) {
                c.send(req("SET", "e" + i, "v", "PX", "50"));
            }
        }
        Thread.sleep(450);                                   // 능동 사이클(100ms 주기) 여러 번
        int after;
        synchronized (server.database()) {
            after = server.database().rawSize();
        }
        assertEquals(baseline, after, "능동 만료가 미접근 키를 정리해야 함");
    }

    private static long intVal(String reply) {
        return Long.parseLong(reply.substring(1, reply.length() - 2));
    }

    private static byte[] req(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "*" + parts.length + "\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            writeAscii(out, "$" + b.length + "\r\n");
            out.writeBytes(b);
            writeAscii(out, "\r\n");
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
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
