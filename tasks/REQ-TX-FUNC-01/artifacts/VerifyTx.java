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

/**
 * REQ-TX-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①[OK,2] ②WATCH 변경→nil ③DISCARD ④EXECABORT
 * + 런타임 오류 원소만 오류(나머지 진행) · +QUEUED · 제어 오류(MULTI 중첩/EXEC·DISCARD without MULTI).
 */
public final class VerifyTx {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-tx: redis4j on port " + port);
        try (Client a = new Client(port); Client b = new Client(port)) {

            // ① MULTI; SET a 1; INCR a; EXEC -> [OK, 2]
            check("MULTI -> +OK", "+OK\r\n", a.send(req("MULTI")));
            check("SET a 1 -> +QUEUED", "+QUEUED\r\n", a.send(req("SET", "a", "1")));
            check("INCR a -> +QUEUED", "+QUEUED\r\n", a.send(req("INCR", "a")));
            checkTree("① EXEC -> [+OK, 2]", list("+OK", 2L), a.sendTree(req("EXEC")));
            check("EXEC 후 GET a -> 2", blk("2"), a.send(req("GET", "a")));

            // ② WATCH 위반 -> nil
            a.send(req("SET", "k", "1"));
            check("WATCH k -> +OK", "+OK\r\n", a.send(req("WATCH", "k")));
            a.send(req("MULTI"));
            check("SET k 2 -> +QUEUED", "+QUEUED\r\n", a.send(req("SET", "k", "2")));
            check("다른 연결 SET k 99 -> +OK", "+OK\r\n", b.send(req("SET", "k", "99")));
            checkTree("② EXEC (감시 위반) -> nil", null, a.sendTree(req("EXEC")));
            check("취소 후 GET k -> 99 (트랜잭션 미적용)", blk("99"), a.send(req("GET", "k")));

            // WATCH 위반 없음 -> 정상 실행
            a.send(req("WATCH", "w"));
            a.send(req("MULTI"));
            a.send(req("SET", "w", "5"));
            checkTree("WATCH 미위반 EXEC -> [+OK]", list("+OK"), a.sendTree(req("EXEC")));
            check("GET w -> 5", blk("5"), a.send(req("GET", "w")));

            // UNWATCH 후에는 변경돼도 실행
            a.send(req("SET", "u", "1"));
            a.send(req("WATCH", "u"));
            a.send(req("UNWATCH"));
            a.send(req("MULTI"));
            a.send(req("SET", "u", "2"));
            b.send(req("SET", "u", "77"));                       // u 변경되지만 UNWATCH 됨
            checkTree("UNWATCH 후 EXEC -> [+OK]", list("+OK"), a.sendTree(req("EXEC")));
            check("GET u -> 2 (트랜잭션 적용)", blk("2"), a.send(req("GET", "u")));

            // ③ DISCARD
            a.send(req("MULTI"));
            check("SET d 1 -> +QUEUED", "+QUEUED\r\n", a.send(req("SET", "d", "1")));
            check("③ DISCARD -> +OK", "+OK\r\n", a.send(req("DISCARD")));
            check("DISCARD 후 GET d -> nil", "$-1\r\n", a.send(req("GET", "d")));
            check("DISCARD 후 MULTI -> +OK (상태 복귀)", "+OK\r\n", a.send(req("MULTI")));
            a.send(req("DISCARD"));

            // ④ 큐잉 중 미지 명령 -> EXECABORT
            a.send(req("MULTI"));
            check("SET x 1 -> +QUEUED", "+QUEUED\r\n", a.send(req("SET", "x", "1")));
            checkTrue("NOTACOMMAND -> ERR unknown",
                    a.send(req("NOTACOMMAND")).startsWith("-ERR unknown command"), "");
            checkTrue("④ EXEC -> EXECABORT",
                    a.send(req("EXEC")).startsWith("-EXECABORT"), "");
            check("EXECABORT 후 GET x -> nil (폐기)", "$-1\r\n", a.send(req("GET", "x")));

            // ⑤ EXEC 중 런타임 오류는 해당 원소만 오류, 나머지 진행(롤백 없음)
            a.send(req("SET", "s", "v"));
            a.send(req("MULTI"));
            a.send(req("SET", "s", "v2"));
            a.send(req("INCR", "s"));                             // "v2" 정수 아님 -> 런타임 오류
            a.send(req("SET", "s2", "ok"));
            checkTree("⑤ EXEC -> [+OK, -ERR, +OK]",
                    list("+OK", "-ERR value is not an integer or out of range", "+OK"),
                    a.sendTree(req("EXEC")));
            check("런타임 오류에도 SET s2 진행 -> ok", blk("ok"), a.send(req("GET", "s2")));

            // 제어 오류
            checkTrue("EXEC without MULTI -> ERR", a.send(req("EXEC")).startsWith("-ERR EXEC without MULTI"), "");
            checkTrue("DISCARD without MULTI -> ERR",
                    a.send(req("DISCARD")).startsWith("-ERR DISCARD without MULTI"), "");
            a.send(req("MULTI"));
            checkTrue("MULTI 중첩 -> ERR", a.send(req("MULTI")).startsWith("-ERR MULTI calls can not be nested"), "");
            a.send(req("DISCARD"));
        } finally {
            server.close();
        }

        System.out.println("---");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static List<Object> list(Object... items) {
        return Arrays.asList(items);
    }

    private static byte[] req(String... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "*" + parts.length + "\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            write(out, "$" + b.length + "\r\n");
            out.writeBytes(b);
            write(out, "\r\n");
        }
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String blk(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return "$" + b.length + "\r\n" + s + "\r\n";
    }

    private static void check(String label, String expect, String actual) {
        if (expect.equals(actual)) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + esc(expect) + " actual=" + esc(actual));
        }
    }

    private static void checkTree(String label, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        if (ok) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + expect + " actual=" + actual);
        }
    }

    private static void checkTrue(String label, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | " + esc(detail));
        }
    }

    private static String esc(String s) {
        return s == null ? "null" : s.replace("\r", "\\r").replace("\n", "\\n");
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
