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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-PUBSUB-FUNC-01 완료조건 ①message ②pmessage ③수신자수 ④종료 시 자동 해제 + 구독 모드 제한. */
class PubSubCommandTest {

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
    void subscribe_receives_message() throws IOException {                    // ①
        try (Client a = new Client(port); Client b = new Client(port)) {
            assertEquals(list("subscribe", "c1", 1L), a.sendTree(req("SUBSCRIBE", "c1")));
            assertEquals(1L, b.sendTree(req("PUBLISH", "c1", "hi")));
            assertEquals(list("message", "c1", "hi"), a.readTree());
        }
    }

    @Test
    void psubscribe_receives_pmessage() throws IOException {                  // ②
        try (Client p = new Client(port); Client b = new Client(port)) {
            assertEquals(list("psubscribe", "news.*", 1L), p.sendTree(req("PSUBSCRIBE", "news.*")));
            assertEquals(1L, b.sendTree(req("PUBLISH", "news.tech", "x")));
            assertEquals(list("pmessage", "news.*", "news.tech", "x"), p.readTree());
        }
    }

    @Test
    void publish_returns_receiver_count() throws IOException {                // ③
        try (Client s1 = new Client(port); Client s2 = new Client(port); Client b = new Client(port)) {
            s1.sendTree(req("SUBSCRIBE", "room"));
            s2.sendTree(req("SUBSCRIBE", "room"));
            assertEquals(2L, b.sendTree(req("PUBLISH", "room", "hey")));
            assertEquals(0L, b.sendTree(req("PUBLISH", "nobody", "hey")));
        }
    }

    @Test
    void disconnect_auto_unsubscribes() throws Exception {                    // ④
        try (Client b = new Client(port)) {
            Client g = new Client(port);
            g.sendTree(req("SUBSCRIBE", "gone"));
            assertEquals(list("gone", 1L), b.sendTree(req("PUBSUB", "NUMSUB", "gone")));
            g.close();
            Thread.sleep(250);
            assertEquals(list("gone", 0L), b.sendTree(req("PUBSUB", "NUMSUB", "gone")));
        }
    }

    @Test
    void subscribe_mode_restricts_commands() throws IOException {
        try (Client a = new Client(port)) {
            a.sendTree(req("SUBSCRIBE", "c1"));
            assertTrue(a.send(req("GET", "x")).startsWith("-ERR Can't execute 'get'"));
            assertEquals(list("pong", ""), a.sendTree(req("PING")));
            assertEquals(list("unsubscribe", "c1", 0L), a.sendTree(req("UNSUBSCRIBE", "c1")));
            assertEquals("$-1\r\n", a.send(req("GET", "x")));                  // 정상 모드 복귀
        }
    }

    private static List<Object> list(Object... items) {
        return Arrays.asList(items);
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

        Object sendTree(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            return readTree();
        }

        Object readTree() throws IOException {
            char t = (char) in.read();
            String head = readLine();
            switch (t) {
                case '+', '-':
                    return t + head;
                case ':':
                    return Long.parseLong(head.trim());
                case '$': {
                    int len = Integer.parseInt(head.trim());
                    if (len < 0) {
                        return null;
                    }
                    byte[] body = in.readNBytes(len);
                    in.read();
                    in.read();
                    return new String(body, StandardCharsets.UTF_8);
                }
                case '*': {
                    int cnt = Integer.parseInt(head.trim());
                    if (cnt < 0) {
                        return null;
                    }
                    List<Object> l = new ArrayList<>(cnt);
                    for (int i = 0; i < cnt; i++) {
                        l.add(readTree());
                    }
                    return l;
                }
                default:
                    return head;
            }
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
