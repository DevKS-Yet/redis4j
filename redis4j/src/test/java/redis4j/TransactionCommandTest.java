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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-TX-FUNC-01 완료조건 ①[OK,2] ②WATCH 위반→nil ③DISCARD ④EXECABORT + 런타임 오류 passthrough. */
class TransactionCommandTest {

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
    void multi_exec_returns_result_array() throws IOException {               // ①
        try (Client a = new Client(port)) {
            assertEquals("+OK\r\n", a.send(req("MULTI")));
            assertEquals("+QUEUED\r\n", a.send(req("SET", "a", "1")));
            assertEquals("+QUEUED\r\n", a.send(req("INCR", "a")));
            assertEquals(list("+OK", 2L), a.sendTree(req("EXEC")));
        }
    }

    @Test
    void watch_violation_aborts_exec() throws IOException {                   // ②
        try (Client a = new Client(port); Client b = new Client(port)) {
            a.send(req("SET", "k", "1"));
            assertEquals("+OK\r\n", a.send(req("WATCH", "k")));
            a.send(req("MULTI"));
            a.send(req("SET", "k", "2"));
            b.send(req("SET", "k", "99"));                                     // 감시 키 변경
            assertNull(a.sendTree(req("EXEC")));                               // null array
            assertEquals(blk("99"), a.send(req("GET", "k")));                  // 트랜잭션 미적용
        }
    }

    @Test
    void discard_clears_queue() throws IOException {                          // ③
        try (Client a = new Client(port)) {
            a.send(req("MULTI"));
            assertEquals("+QUEUED\r\n", a.send(req("SET", "d", "1")));
            assertEquals("+OK\r\n", a.send(req("DISCARD")));
            assertEquals("$-1\r\n", a.send(req("GET", "d")));
        }
    }

    @Test
    void queue_time_error_causes_execabort() throws IOException {             // ④
        try (Client a = new Client(port)) {
            a.send(req("MULTI"));
            a.send(req("SET", "x", "1"));
            assertTrue(a.send(req("NOTACOMMAND")).startsWith("-ERR unknown command"));
            assertTrue(a.send(req("EXEC")).startsWith("-EXECABORT"));
            assertEquals("$-1\r\n", a.send(req("GET", "x")));                  // 폐기
        }
    }

    @Test
    void runtime_error_does_not_abort_others() throws IOException {           // ⑤
        try (Client a = new Client(port)) {
            a.send(req("MULTI"));
            a.send(req("SET", "s", "v2"));
            a.send(req("INCR", "s"));                                          // 정수 아님 → 런타임 오류
            a.send(req("SET", "s2", "ok"));
            assertEquals(list("+OK", "-ERR value is not an integer or out of range", "+OK"),
                    a.sendTree(req("EXEC")));
            assertEquals(blk("ok"), a.send(req("GET", "s2")));
        }
    }

    private static List<Object> list(Object... items) {
        return Arrays.asList(items);
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

        Object sendTree(byte[] request) throws IOException {
            out.write(request);
            out.flush();
            return readTree();
        }

        private Object readTree() throws IOException {
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
