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
import java.util.TreeSet;

/**
 * REQ-PUBSUB-FUNC-01 JDK 전용 검증 하니스. 완료조건 ①message 수신 ②pmessage(패턴) ③PUBLISH 수신자수
 * ④연결 종료 시 자동 구독 해제 + 구독 모드 명령 제한 · PING(배열) · PUBSUB CHANNELS/NUMSUB/NUMPAT.
 * 여러 영속 연결(구독자/발행자)을 사용하고, 밀려오는 message 는 별도로 읽는다.
 */
public final class VerifyPubSub {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        RedisServer server = new RedisServer(0);
        int port = server.start();
        System.out.println("verify-pubsub: redis4j on port " + port);
        List<Client> open = new ArrayList<>();
        try {
            Client b = client(port, open);                       // 발행자

            // ① SUBSCRIBE + message 수신
            Client a = client(port, open);
            checkTree("① SUBSCRIBE c1 확인", list("subscribe", "c1", 1L), a.sendTree(req("SUBSCRIBE", "c1")));
            checkTree("PUBLISH c1 hi -> :1", 1L, b.sendTree(req("PUBLISH", "c1", "hi")));
            checkTree("① A message 수신", list("message", "c1", "hi"), a.readTree());

            // ② PSUBSCRIBE + pmessage 수신
            Client p = client(port, open);
            checkTree("② PSUBSCRIBE news.* 확인", list("psubscribe", "news.*", 1L),
                    p.sendTree(req("PSUBSCRIBE", "news.*")));
            checkTree("PUBLISH news.tech x -> :1", 1L, b.sendTree(req("PUBLISH", "news.tech", "x")));
            checkTree("② P pmessage 수신", list("pmessage", "news.*", "news.tech", "x"), p.readTree());

            // ③ PUBLISH 반환값 = 수신 구독자 수
            Client s1 = client(port, open);
            Client s2 = client(port, open);
            s1.sendTree(req("SUBSCRIBE", "room"));
            s2.sendTree(req("SUBSCRIBE", "room"));
            checkTree("③ PUBLISH room hey -> :2", 2L, b.sendTree(req("PUBLISH", "room", "hey")));
            s1.readTree();
            s2.readTree();
            checkTree("PUBLISH nobody hey -> :0", 0L, b.sendTree(req("PUBLISH", "nobody", "hey")));

            // 구독 모드 명령 제한 · PING(배열) · UNSUBSCRIBE 후 복귀
            checkTrue("구독 중 GET -> ERR 제한",
                    a.send(req("GET", "x")).startsWith("-ERR Can't execute 'get'"), "");
            checkTree("구독 중 PING -> [pong,\"\"]", list("pong", ""), a.sendTree(req("PING")));
            checkTree("구독 중 PING hello -> [pong,hello]", list("pong", "hello"),
                    a.sendTree(req("PING", "hello")));
            checkTree("UNSUBSCRIBE c1 -> count 0", list("unsubscribe", "c1", 0L),
                    a.sendTree(req("UNSUBSCRIBE", "c1")));
            check("UNSUBSCRIBE 후 GET x -> nil(정상 모드)", "$-1\r\n", a.send(req("GET", "x")));

            // PUBSUB CHANNELS / NUMSUB / NUMPAT
            checkSet("PUBSUB CHANNELS -> room 포함, c1 미포함", set("room"),
                    treeToSet(b.sendTree(req("PUBSUB", "CHANNELS"))));
            checkTree("PUBSUB NUMSUB room -> [room,2]", list("room", 2L),
                    b.sendTree(req("PUBSUB", "NUMSUB", "room")));
            checkTree("PUBSUB NUMPAT -> :1", 1L, b.sendTree(req("PUBSUB", "NUMPAT")));

            // ④ 연결 종료 시 자동 구독 해제
            Client g = client(port, open);
            g.sendTree(req("SUBSCRIBE", "gone"));
            checkTree("SUBSCRIBE gone 후 NUMSUB -> [gone,1]", list("gone", 1L),
                    b.sendTree(req("PUBSUB", "NUMSUB", "gone")));
            g.close();
            Thread.sleep(250);                                   // 서버가 EOF 감지·정리할 시간
            checkTree("④ 연결 종료 후 NUMSUB gone -> [gone,0]", list("gone", 0L),
                    b.sendTree(req("PUBSUB", "NUMSUB", "gone")));
        } finally {
            for (Client c : open) {
                try {
                    c.close();
                } catch (IOException ignored) {
                    // 무시
                }
            }
            server.close();
        }

        System.out.println("---");
        System.out.println("PASS=" + passed + " FAIL=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static Client client(int port, List<Client> open) throws IOException {
        Client c = new Client(port);
        open.add(c);
        return c;
    }

    /** 기대 트리 빌더: String/Long 원소를 List 로. */
    private static List<Object> list(Object... items) {
        return Arrays.asList(items);
    }

    private static TreeSet<String> set(String... items) {
        TreeSet<String> s = new TreeSet<>();
        for (String i : items) {
            s.add(i);
        }
        return s;
    }

    private static TreeSet<String> treeToSet(Object tree) {
        TreeSet<String> s = new TreeSet<>();
        for (Object o : (List<?>) tree) {
            s.add((String) o);
        }
        return s;
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

    private static void checkTree(String label, Object expect, Object actual) {
        if (expect.equals(actual)) {
            passed++;
            System.out.println("PASS " + label);
        } else {
            failed++;
            System.out.println("FAIL " + label + " | expected=" + expect + " actual=" + actual);
        }
    }

    private static void checkSet(String label, TreeSet<String> expect, TreeSet<String> actual) {
        if (expect.equals(actual)) {
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
