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

/** REQ-LIST-FUNC-01 완료조건 ①②③④ + 핵심 명령 검증. */
class ListCommandTest {

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
    void rpush_and_lrange() throws IOException {                        // ①
        try (Client c = new Client(port)) {
            assertEquals(":3\r\n", c.send(req("RPUSH", "l", "a", "b", "c")));
            assertEquals("*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", c.send(req("LRANGE", "l", "0", "-1")));
        }
    }

    @Test
    void lpop_and_llen() throws IOException {                           // ②
        try (Client c = new Client(port)) {
            c.send(req("RPUSH", "l", "a", "b", "c"));
            assertEquals("$1\r\na\r\n", c.send(req("LPOP", "l")));
            assertEquals(":2\r\n", c.send(req("LLEN", "l")));
        }
    }

    @Test
    void emptied_list_is_removed() throws IOException {                 // ③
        try (Client c = new Client(port)) {
            c.send(req("RPUSH", "l", "a", "b"));
            c.send(req("LPOP", "l"));
            c.send(req("LPOP", "l"));
            assertEquals(":0\r\n", c.send(req("EXISTS", "l")));
        }
    }

    @Test
    void wrong_type_both_ways() throws IOException {                    // ④ + STR③ 종단
        try (Client c = new Client(port)) {
            c.send(req("SET", "s", "v"));
            assertTrue(c.send(req("LPUSH", "s", "x")).startsWith("-WRONGTYPE"));
            c.send(req("RPUSH", "ll", "a"));
            assertTrue(c.send(req("GET", "ll")).startsWith("-WRONGTYPE"));
        }
    }

    @Test
    void lpush_reverses_order() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("LPUSH", "m", "a", "b", "c"));
            assertEquals("*3\r\n$1\r\nc\r\n$1\r\nb\r\n$1\r\na\r\n", c.send(req("LRANGE", "m", "0", "-1")));
        }
    }

    @Test
    void lrem_and_linsert() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("RPUSH", "r", "a", "b", "a", "c", "a"));
            assertEquals(":2\r\n", c.send(req("LREM", "r", "2", "a")));
            assertEquals("*3\r\n$1\r\nb\r\n$1\r\nc\r\n$1\r\na\r\n", c.send(req("LRANGE", "r", "0", "-1")));
            c.send(req("RPUSH", "i", "a", "c"));
            assertEquals(":3\r\n", c.send(req("LINSERT", "i", "BEFORE", "c", "b")));
            assertEquals("*3\r\n$1\r\na\r\n$1\r\nb\r\n$1\r\nc\r\n", c.send(req("LRANGE", "i", "0", "-1")));
            assertEquals(":-1\r\n", c.send(req("LINSERT", "i", "AFTER", "zzz", "x")));
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
