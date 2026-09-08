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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-RDB-FUNC-01 완료조건 ①SAVE→파일 ②재시작→복원(값·TTL·다중DB) ③원자적 저장 ④빈 데이터셋. */
class RdbPersistenceTest {

    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("rdb4j-junit");
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var s = Files.walk(dir)) {
            s.sorted((x, y) -> y.getNameCount() - x.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 무시
                }
            });
        }
    }

    @Test
    void save_creates_file_and_restart_restores() throws IOException {        // ①②
        Path rdb = dir.resolve("dump.rdb4j");
        RedisServer s1 = new RedisServer(0, rdb);
        int p1 = s1.start();
        try (Client c = new Client(p1)) {
            c.send(req("SET", "s", "hello"));
            c.send(req("RPUSH", "l", "a", "b", "c"));
            c.send(req("SET", "tvol", "x", "PX", "100000"));
            c.send(req("SELECT", "1"));
            c.send(req("SET", "d1", "v1"));
            c.send(req("SELECT", "0"));
            assertEquals("+OK\r\n", c.send(req("SAVE")));
            assertTrue(Files.exists(rdb));                                    // ①
        } finally {
            s1.close();
        }

        RedisServer s2 = new RedisServer(0, rdb);
        int p2 = s2.start();
        try (Client c = new Client(p2)) {
            assertEquals(blk("hello"), c.send(req("GET", "s")));              // ②
            assertEquals(list("a", "b", "c"), c.sendTree(req("LRANGE", "l", "0", "-1")));
            long pttl = Long.parseLong(c.send(req("PTTL", "tvol")).replaceAll("[^0-9-]", ""));
            assertTrue(pttl > 0 && pttl <= 100000);                          // TTL 복원
            c.send(req("SELECT", "1"));
            assertEquals(blk("v1"), c.send(req("GET", "d1")));               // 다중 DB 격리 복원
        } finally {
            s2.close();
        }
    }

    @Test
    void atomic_save_leaves_no_temp() throws IOException {                    // ③
        Path rdb = dir.resolve("dump.rdb4j");
        RedisServer s = new RedisServer(0, rdb);
        int p = s.start();
        try (Client c = new Client(p)) {
            c.send(req("SET", "k", "v"));
            assertEquals("+OK\r\n", c.send(req("SAVE")));
            try (var files = Files.list(dir)) {
                assertTrue(files.noneMatch(f -> f.getFileName().toString().endsWith(".tmp")));
            }
        } finally {
            s.close();
        }
    }

    @Test
    void empty_dataset_save_and_load() throws IOException {                   // ④
        Path rdb = dir.resolve("empty.rdb4j");
        RedisServer s1 = new RedisServer(0, rdb);
        int p1 = s1.start();
        try (Client c = new Client(p1)) {
            assertEquals("+OK\r\n", c.send(req("SAVE")));
            assertEquals(":0\r\n", c.send(req("DBSIZE")));
        } finally {
            s1.close();
        }
        RedisServer s2 = new RedisServer(0, rdb);
        int p2 = s2.start();
        try (Client c = new Client(p2)) {
            assertEquals(":0\r\n", c.send(req("DBSIZE")));
        } finally {
            s2.close();
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
