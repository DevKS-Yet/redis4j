import redis4j.server.RedisServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * REQ-EXP-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①②③④ + 확장을 실제 소켓으로 판정.
 * ④(능동 만료)는 신선한 서버에 만료 키 다수를 넣고 <b>접근하지 않은 채</b> rawSize 감소로 판정.
 */
public final class VerifyExp {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-exp: redis4j on port " + port);
        try {
            // ① EXPIRE + lazy 만료
            check("① SET k v -> +OK", "+OK\r\n", one(port, req("SET", "k", "v")));
            check("① PEXPIRE k 100 -> :1", ":1\r\n", one(port, req("PEXPIRE", "k", "100")));
            check("① GET k (즉시) -> v", "$1\r\nv\r\n", one(port, req("GET", "k")));
            Thread.sleep(160);
            check("① GET k (만료 후) -> nil", "$-1\r\n", one(port, req("GET", "k")));

            // ② TTL 규약
            check("② TTL absent -> -2", ":-2\r\n", one(port, req("TTL", "absent")));
            check("  SET p v -> +OK", "+OK\r\n", one(port, req("SET", "p", "v")));
            check("② TTL p (영구) -> -1", ":-1\r\n", one(port, req("TTL", "p")));
            check("  PTTL p (영구) -> -1", ":-1\r\n", one(port, req("PTTL", "p")));

            // ③ PERSIST
            check("  SET q v -> +OK", "+OK\r\n", one(port, req("SET", "q", "v")));
            check("  EXPIRE q 100 -> :1", ":1\r\n", one(port, req("EXPIRE", "q", "100")));
            long ttlq = intVal(one(port, req("TTL", "q")));
            checkTrue("③ TTL q ~100 (99~100)", ttlq >= 99 && ttlq <= 100, "ttl=" + ttlq);
            check("③ PERSIST q -> :1", ":1\r\n", one(port, req("PERSIST", "q")));
            check("③ TTL q -> -1 (영구)", ":-1\r\n", one(port, req("TTL", "q")));
            check("③ GET q -> v (유지)", "$1\r\nv\r\n", one(port, req("GET", "q")));
            check("③ PERSIST q 재차 -> :0", ":0\r\n", one(port, req("PERSIST", "q")));

            // 확장: SET EX + TTL (STR 단계 완료조건 ④ 종단 확인)
            check("  SET e v EX 100 -> +OK", "+OK\r\n", one(port, req("SET", "e", "v", "EX", "100")));
            long ttle = intVal(one(port, req("TTL", "e")));
            checkTrue("SET EX 100 후 TTL ~100", ttle >= 99 && ttle <= 100, "ttl=" + ttle);

            // 확장: EXPIRE 없는 키 -> :0 ; PEXPIREAT 과거 -> 삭제
            check("EXPIRE absent -> :0", ":0\r\n", one(port, req("EXPIRE", "absent", "100")));
            check("  SET z v -> +OK", "+OK\r\n", one(port, req("SET", "z", "v")));
            check("PEXPIREAT z 1(과거) -> :1", ":1\r\n", one(port, req("PEXPIREAT", "z", "1")));
            check("  GET z (과거만료) -> nil", "$-1\r\n", one(port, req("GET", "z")));
        } finally {
            server.close();
        }

        // ④ 능동 만료 — 신선한 서버, 200키 미접근
        RedisServer s2 = new RedisServer(0);
        int p2 = s2.start();
        try {
            int baseline;
            synchronized (s2.database()) {
                baseline = s2.database().rawSize();
            }
            try (Client c = new Client(p2)) {
                for (int i = 0; i < 200; i++) {
                    c.send(req("SET", "e" + i, "v", "PX", "50"));   // 50ms 후 만료
                }
            }
            int afterSet;
            synchronized (s2.database()) {
                afterSet = s2.database().rawSize();
            }
            Thread.sleep(450);                                       // 능동 사이클 여러 번(100ms 주기)
            int afterExpire;
            synchronized (s2.database()) {
                afterExpire = s2.database().rawSize();
            }
            checkTrue("④ 200키 저장 확인 (rawSize " + baseline + "->" + afterSet + ")", afterSet - baseline == 200, "afterSet=" + afterSet);
            checkTrue("④ 미접근 상태로 능동 만료됨 (rawSize " + afterSet + "->" + afterExpire + ")", afterExpire == 0, "afterExpire=" + afterExpire);
            // 능동 만료 중에도 명령 정상 응답
            check("④ 만료 처리 중 PING 정상", "+PONG\r\n", one(p2, req("PING")));
        } finally {
            s2.close();
        }

        System.out.println("---");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static long intVal(String reply) {
        return Long.parseLong(reply.substring(1, reply.length() - 2));   // ":<n>\r\n"
    }

    private static String one(int port, byte[] request) throws IOException {
        try (Client c = new Client(port)) {
            return c.send(request);
        }
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

    private static void check(String label, String expect, String actual) {
        if (expect.equals(actual)) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + esc(expect) + " actual=" + esc(actual));
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
        return s.replace("\r", "\\r").replace("\n", "\\n");
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
