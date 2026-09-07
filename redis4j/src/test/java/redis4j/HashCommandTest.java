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

/** REQ-HASH-FUNC-01 완료조건 ①②③④ + 핵심 명령 검증. */
class HashCommandTest {

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
    void hset_and_hget() throws IOException {                          // ①
        try (Client c = new Client(port)) {
            assertEquals(":2\r\n", c.send(req("HSET", "h", "f1", "v1", "f2", "v2")));
            assertEquals("$2\r\nv1\r\n", c.send(req("HGET", "h", "f1")));
        }
    }

    @Test
    void hgetall_insertion_order() throws IOException {                // ②
        try (Client c = new Client(port)) {
            c.send(req("HSET", "h", "f1", "v1", "f2", "v2"));
            assertEquals("*4\r\n$2\r\nf1\r\n$2\r\nv1\r\n$2\r\nf2\r\n$2\r\nv2\r\n",
                    c.send(req("HGETALL", "h")));
        }
    }

    @Test
    void hdel_then_hexists() throws IOException {                      // ③
        try (Client c = new Client(port)) {
            c.send(req("HSET", "h", "f1", "v1", "f2", "v2"));
            assertEquals(":1\r\n", c.send(req("HDEL", "h", "f1")));
            assertEquals(":0\r\n", c.send(req("HEXISTS", "h", "f1")));
        }
    }

    @Test
    void hincrby() throws IOException {                                // ④
        try (Client c = new Client(port)) {
            assertEquals(":5\r\n", c.send(req("HINCRBY", "h", "cnt", "5")));
            assertEquals(":8\r\n", c.send(req("HINCRBY", "h", "cnt", "3")));
        }
    }

    @Test
    void hsetnx_and_update_returns_zero() throws IOException {
        try (Client c = new Client(port)) {
            assertEquals(":1\r\n", c.send(req("HSETNX", "h", "a", "1")));
            assertEquals(":0\r\n", c.send(req("HSETNX", "h", "a", "2")));
            assertEquals("$1\r\n1\r\n", c.send(req("HGET", "h", "a")));
            assertEquals(":0\r\n", c.send(req("HSET", "h", "a", "9")));   // 갱신 → 0 new
        }
    }

    @Test
    void wrong_type_both_ways() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SET", "s", "v"));
            assertTrue(c.send(req("HSET", "s", "f", "v")).startsWith("-WRONGTYPE"));
            c.send(req("HSET", "h", "f", "v"));
            assertTrue(c.send(req("GET", "h")).startsWith("-WRONGTYPE"));
            assertEquals("+hash\r\n", c.send(req("TYPE", "h")));
        }
    }

    @Test
    void emptied_hash_is_removed() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("HSET", "he", "only", "1"));
            assertEquals(":1\r\n", c.send(req("HDEL", "he", "only")));
            assertEquals(":0\r\n", c.send(req("EXISTS", "he")));
        }
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
