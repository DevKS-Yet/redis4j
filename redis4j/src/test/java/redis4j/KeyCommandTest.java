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
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REQ-KEY-FUNC-01 완료조건 ①KEYS ②SCAN ③SELECT 격리 ④FLUSHDB + RENAME/SWAPDB. */
class KeyCommandTest {

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
    void keys_glob() throws IOException {                                     // ①
        try (Client c = new Client(port)) {
            c.send(req("MSET", "a", "1", "b", "2"));
            assertEquals(set("a", "b"), keySet(c, req("KEYS", "*")));
            assertEquals(set("a"), keySet(c, req("KEYS", "a")));
            c.send(req("FLUSHDB"));
            c.send(req("MSET", "hello", "1", "hallo", "2", "hxllo", "3", "hllo", "4"));
            assertEquals(set("hello", "hallo", "hxllo"), keySet(c, req("KEYS", "h?llo")));
            assertEquals(set("hello", "hallo"), keySet(c, req("KEYS", "h[ae]llo")));
        }
    }

    @Test
    void scan_full_iteration() throws IOException {                          // ②
        try (Client c = new Client(port)) {
            String[] mset = new String[21];
            mset[0] = "MSET";
            TreeSet<String> expected = new TreeSet<>();
            for (int i = 0; i < 10; i++) {
                mset[1 + i * 2] = "k" + i;
                mset[2 + i * 2] = "v";
                expected.add("k" + i);
            }
            c.send(req(mset));
            assertEquals(expected, scanAll(c, "3"));
        }
    }

    @Test
    void select_isolation() throws IOException {                             // ③
        try (Client c = new Client(port)) {
            c.send(req("SET", "g", "v0"));                                    // db0
            assertEquals("+OK\r\n", c.send(req("SELECT", "1")));
            assertEquals("$-1\r\n", c.send(req("GET", "g")));                 // 격리
            c.send(req("SET", "g", "v1"));                                    // db1
            assertEquals(blk("v1"), c.send(req("GET", "g")));
            assertEquals("+OK\r\n", c.send(req("SELECT", "0")));
            assertEquals(blk("v0"), c.send(req("GET", "g")));
        }
    }

    @Test
    void flushdb_then_dbsize_zero() throws IOException {                     // ④
        try (Client c = new Client(port)) {
            c.send(req("MSET", "x", "1", "y", "2"));
            assertEquals("+OK\r\n", c.send(req("FLUSHDB")));
            assertEquals(":0\r\n", c.send(req("DBSIZE")));
        }
    }

    @Test
    void rename_and_renamenx() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SET", "src", "1"));
            assertEquals("+OK\r\n", c.send(req("RENAME", "src", "dst")));
            assertEquals(":0\r\n", c.send(req("EXISTS", "src")));
            assertEquals(blk("1"), c.send(req("GET", "dst")));
            assertTrue(c.send(req("RENAME", "nokey", "x")).startsWith("-ERR no such key"));
            c.send(req("SET", "p", "1"));
            assertEquals(":0\r\n", c.send(req("RENAMENX", "p", "dst")));       // dst 존재
            assertEquals(":1\r\n", c.send(req("RENAMENX", "p", "r")));
        }
    }

    @Test
    void swapdb_swaps_contents() throws IOException {
        try (Client c = new Client(port)) {
            c.send(req("SET", "g", "a"));                                     // db0
            c.send(req("SELECT", "1"));
            c.send(req("SET", "g", "b"));                                     // db1
            c.send(req("SELECT", "0"));
            assertEquals("+OK\r\n", c.send(req("SWAPDB", "0", "1")));
            assertEquals(blk("b"), c.send(req("GET", "g")));                  // db0 이 db1 내용
        }
    }

    private static TreeSet<String> keySet(Client c, byte[] request) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        for (Object k : (List<?>) c.sendTree(request)) {
            out.add((String) k);
        }
        return out;
    }

    private static TreeSet<String> scanAll(Client c, String count) throws IOException {
        TreeSet<String> acc = new TreeSet<>();
        String cursor = "0";
        do {
            Object o = c.sendTree(req("SCAN", cursor, "COUNT", count));
            List<?> l = (List<?>) o;
            cursor = (String) l.get(0);
            for (Object k : (List<?>) l.get(1)) {
                acc.add((String) k);
            }
        } while (!cursor.equals("0"));
        return acc;
    }

    private static TreeSet<String> set(String... items) {
        TreeSet<String> s = new TreeSet<>();
        for (String i : items) {
            s.add(i);
        }
        return s;
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
