package redis4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-STR-FUNC-01 완료조건 ①②③④ + 핵심 동작 검증. */
class StringCommandTest {

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
    void set_and_get() throws IOException {                                   // ①
        try (Client c = new Client(port)) {
            assertEquals("+OK\r\n", c.send(req("SET", "foo", "bar")));
            assertEquals("$3\r\nbar\r\n", c.send(req("GET", "foo")));
        }
    }

    @Test
    void incr_and_non_integer_error() throws IOException {                    // ②
        try (Client c = new Client(port)) {
            assertEquals("+OK\r\n", c.send(req("SET", "n", "10")));
            assertEquals(":11\r\n", c.send(req("INCR", "n")));
            assertEquals("+OK\r\n", c.send(req("SET", "s", "abc")));
            assertTrue(c.send(req("INCR", "s")).startsWith("-ERR value is not an integer"));
        }
    }

    @Test
    void wrong_type_on_non_string() throws IOException {                      // ③
        synchronized (server.database()) {
            server.database().put("mylist", () -> RedisType.LIST, 0L);
        }
        try (Client c = new Client(port)) {
            assertTrue(c.send(req("GET", "mylist")).startsWith("-WRONGTYPE"));
            assertEquals("+list\r\n", c.send(req("TYPE", "mylist")));
        }
    }

    @Test
    void set_px_expires_lazily() throws IOException, InterruptedException {    // ④
        try (Client c = new Client(port)) {
            assertEquals("+OK\r\n", c.send(req("SET", "k", "v", "PX", "100")));
            assertEquals("$1\r\nv\r\n", c.send(req("GET", "k")));
            Thread.sleep(160);
            assertEquals("$-1\r\n", c.send(req("GET", "k")));
        }
    }

    @Test
    void binary_safe_value() throws IOException {
        try (Client c = new Client(port)) {
            assertEquals("+OK\r\n", c.send(req("SET", "b", "a\nb")));
            assertEquals("$3\r\na\nb\r\n", c.send(req("GET", "b")));
        }
    }

    @Test
    void mget_mixed() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("MSET", "x", "1", "y", "2"));
            assertEquals("*3\r\n$1\r\n1\r\n$1\r\n2\r\n$-1\r\n", c.send(req("MGET", "x", "y", "nope")));
        }
    }

    @Test
    void concurrent_incr_is_atomic() throws Exception {
        try (Client init = new Client(port)) {
            init.send(req("DEL", "c"));
        }
        int n = 100;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    try (Client c = new Client(port)) {
                        return c.send(req("INCR", "c"));
                    }
                }));
            }
            for (Future<String> f : fs) {
                assertTrue(f.get(5, TimeUnit.SECONDS).startsWith(":"));
            }
        } finally {
            pool.shutdownNow();
        }
        try (Client c = new Client(port)) {
            assertEquals("$3\r\n100\r\n", c.send(req("GET", "c")));
        }
    }

    // ---- 최소 소켓 클라이언트 ----

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
