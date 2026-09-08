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

/** REQ-ZSET-FUNC-01 완료조건 ①②③④ + 정렬/범위/플래그 검증(순서 정확 비교). */
class ZSetCommandTest {

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
    void zadd_and_zrange() throws IOException {                        // ①
        try (Client c = new Client(port)) {
            assertEquals(":3\r\n", c.send(req("ZADD", "z", "1", "a", "2", "b", "3", "c")));
            assertEquals(arr("a", "b", "c"), c.send(req("ZRANGE", "z", "0", "-1")));
            assertEquals(arr("a", "1", "b", "2", "c", "3"), c.send(req("ZRANGE", "z", "0", "-1", "WITHSCORES")));
        }
    }

    @Test
    void zrangebyscore() throws IOException {                          // ②
        try (Client c = new Client(port)) {
            c.send(req("ZADD", "z", "1", "a", "2", "b", "3", "c"));
            assertEquals(arr("b", "c"), c.send(req("ZRANGEBYSCORE", "z", "2", "3")));
            assertEquals(arr("b", "c"), c.send(req("ZRANGEBYSCORE", "z", "(1", "3")));
        }
    }

    @Test
    void zrank() throws IOException {                                  // ③
        try (Client c = new Client(port)) {
            c.send(req("ZADD", "z", "1", "a", "2", "b", "3", "c"));
            assertEquals(":1\r\n", c.send(req("ZRANK", "z", "b")));
            assertEquals("$-1\r\n", c.send(req("ZRANK", "z", "nope")));
        }
    }

    @Test
    void zincrby_then_zscore() throws IOException {                    // ④
        try (Client c = new Client(port)) {
            c.send(req("ZADD", "z", "1", "a"));
            assertEquals(blk("6"), c.send(req("ZINCRBY", "z", "5", "a")));
            assertEquals(blk("6"), c.send(req("ZSCORE", "z", "a")));
        }
    }

    @Test
    void zadd_flags() throws IOException {
        try (Client c = new Client(port)) {
            assertEquals(":0\r\n", c.send(req("ZADD", "f", "XX", "1", "new")));    // XX: 신규 스킵
            assertEquals(":1\r\n", c.send(req("ZADD", "f", "NX", "1", "a")));
            assertEquals(":0\r\n", c.send(req("ZADD", "f", "NX", "2", "a")));      // NX: 기존 스킵
            assertEquals(blk("1"), c.send(req("ZSCORE", "f", "a")));
            assertEquals(":0\r\n", c.send(req("ZADD", "f", "GT", "5", "a")));      // GT: 5>1 갱신
            assertEquals(blk("5"), c.send(req("ZSCORE", "f", "a")));
            assertEquals(":1\r\n", c.send(req("ZADD", "f", "CH", "10", "a")));     // CH: 변경 카운트
            assertEquals(blk("15"), c.send(req("ZADD", "f", "INCR", "5", "a")));   // INCR
        }
    }

    @Test
    void zremrangebyrank_and_wrongtype() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("ZADD", "rr", "1", "a", "2", "b", "3", "c", "4", "d"));
            assertEquals(":2\r\n", c.send(req("ZREMRANGEBYRANK", "rr", "0", "1")));
            assertEquals(arr("c", "d"), c.send(req("ZRANGE", "rr", "0", "-1")));
            c.send(req("SET", "s", "v"));
            assertTrue(c.send(req("ZADD", "s", "1", "a")).startsWith("-WRONGTYPE"));
            assertTrue(c.send(req("GET", "rr")).startsWith("-WRONGTYPE"));
        }
    }

    private static String arr(String... items) {
        StringBuilder sb = new StringBuilder("*").append(items.length).append("\r\n");
        for (String it : items) {
            byte[] b = it.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(it).append("\r\n");
        }
        return sb.toString();
    }

    private static String blk(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return "$" + b.length + "\r\n" + s + "\r\n";
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
