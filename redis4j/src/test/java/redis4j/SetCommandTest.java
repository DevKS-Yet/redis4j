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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-SET-FUNC-01 완료조건 ①②③④ + 핵심 명령 검증(집합 반환은 순서 비의존). */
class SetCommandTest {

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
    void sadd_dedup_and_scard() throws IOException {                   // ①
        try (Client c = new Client(port)) {
            assertEquals(":2\r\n", c.send(req("SADD", "s", "a", "b", "a")));
            assertEquals(":2\r\n", c.send(req("SCARD", "s")));
        }
    }

    @Test
    void sismember() throws IOException {                              // ②
        try (Client c = new Client(port)) {
            c.send(req("SADD", "s", "a", "b"));
            assertEquals(":1\r\n", c.send(req("SISMEMBER", "s", "a")));
            assertEquals(":0\r\n", c.send(req("SISMEMBER", "s", "z")));
        }
    }

    @Test
    void sinter_sunion_sdiff() throws IOException {                    // ③
        try (Client c = new Client(port)) {
            c.send(req("SADD", "s1", "a", "b", "c"));
            c.send(req("SADD", "s2", "b", "c", "d"));
            assertEquals(Set.of("b", "c"), parseSet(c.send(req("SINTER", "s1", "s2"))));
            assertEquals(Set.of("a", "b", "c", "d"), parseSet(c.send(req("SUNION", "s1", "s2"))));
            assertEquals(Set.of("a"), parseSet(c.send(req("SDIFF", "s1", "s2"))));
        }
    }

    @Test
    void srem_empties_and_deletes() throws IOException {               // ④
        try (Client c = new Client(port)) {
            c.send(req("SADD", "s", "a", "b"));
            assertEquals(":2\r\n", c.send(req("SREM", "s", "a", "b")));
            assertEquals(":0\r\n", c.send(req("EXISTS", "s")));
        }
    }

    @Test
    void sinterstore_and_smove() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SADD", "s1", "a", "b", "c"));
            c.send(req("SADD", "s2", "b", "c", "d"));
            assertEquals(":2\r\n", c.send(req("SINTERSTORE", "d", "s1", "s2")));
            assertEquals(Set.of("b", "c"), parseSet(c.send(req("SMEMBERS", "d"))));
            c.send(req("SADD", "ma", "x", "y"));
            assertEquals(":1\r\n", c.send(req("SMOVE", "ma", "mb", "x")));
            assertEquals(":1\r\n", c.send(req("SISMEMBER", "mb", "x")));
            assertEquals(":0\r\n", c.send(req("SISMEMBER", "ma", "x")));
        }
    }

    @Test
    void wrong_type_both_ways() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SET", "str", "v"));
            assertTrue(c.send(req("SADD", "str", "x")).startsWith("-WRONGTYPE"));
            c.send(req("SADD", "set", "a"));
            assertTrue(c.send(req("GET", "set")).startsWith("-WRONGTYPE"));
        }
    }

    private static Set<String> parseSet(String wire) {
        Set<String> out = new HashSet<>();
        int i = wire.indexOf("\r\n") + 2;
        while (i < wire.length() && wire.charAt(i) == '$') {
            int nl = wire.indexOf("\r\n", i);
            int len = Integer.parseInt(wire.substring(i + 1, nl));
            int start = nl + 2;
            if (len < 0) {
                i = start;
                continue;
            }
            out.add(wire.substring(start, start + len));
            i = start + len + 2;
        }
        return out;
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
