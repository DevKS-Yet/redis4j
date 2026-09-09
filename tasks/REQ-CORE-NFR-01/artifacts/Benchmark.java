import redis4j.server.RedisServer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * REQ-CORE-NFR-01 기준 벤치마크(④). 파이프라인 없이(요청→응답→다음) N 개의 동시 클라이언트가
 * SET/GET 을 반복하며 집계 처리량(QPS)과 지연 백분위(p50/p99)를 측정한다. loopback 왕복 기준,
 * 초기 목표(수만 QPS·p99 수 ms)의 baseline 을 산출한다. 수치는 머신·부하에 따라 달라질 수 있다.
 */
public final class Benchmark {

    public static void main(String[] args) throws Exception {
        int clients = intArg(args, 0, 32);
        int opsPerClient = intArg(args, 1, 20_000);

        try (RedisServer server = new RedisServer(0)) {
            int port = server.start();
            System.out.println("redis4j benchmark — 비파이프라인, loopback");
            System.out.println("clients=" + clients + " opsPerClient=" + opsPerClient
                    + " (total " + (long) clients * opsPerClient + " ops/type)");
            System.out.println("JVM=" + System.getProperty("java.version")
                    + " cpus=" + Runtime.getRuntime().availableProcessors());
            System.out.println("---");

            run(port, clients, opsPerClient / 4, "SET", true);   // warmup(측정 제외)
            Result set = run(port, clients, opsPerClient, "SET", false);
            Result get = run(port, clients, opsPerClient, "GET", false);

            System.out.println(set.format());
            System.out.println(get.format());
        }
    }

    private static Result run(int port, int clients, int ops, String op, boolean warmup)
            throws Exception {
        long[][] lat = new long[clients][];
        Thread[] ts = new Thread[clients];
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        for (int c = 0; c < clients; c++) {
            final int id = c;
            ts[c] = Thread.ofVirtual().start(() -> {
                try (Conn conn = new Conn(port)) {
                    long[] samples = new long[ops];
                    String key = "bench:" + id;
                    if (op.equals("GET")) {
                        conn.roundtrip(set(key, "v"));           // GET 대상 사전 적재
                    }
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < ops; i++) {
                        String req = op.equals("SET") ? set(key, "v" + i) : get(key);
                        long t0 = System.nanoTime();
                        conn.roundtrip(req);
                        samples[i] = System.nanoTime() - t0;
                    }
                    lat[id] = samples;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Thread t : ts) {
            t.join();
        }
        long wallNanos = System.nanoTime() - start;
        return new Result(op, warmup, (long) clients * ops, wallNanos, merge(lat));
    }

    private static long[] merge(long[][] parts) {
        int n = 0;
        for (long[] p : parts) {
            n += p.length;
        }
        long[] all = new long[n];
        int off = 0;
        for (long[] p : parts) {
            System.arraycopy(p, 0, all, off, p.length);
            off += p.length;
        }
        Arrays.sort(all);
        return all;
    }

    private record Result(String op, boolean warmup, long ops, long wallNanos, long[] sorted) {
        String format() {
            double secs = wallNanos / 1e9;
            long qps = Math.round(ops / secs);
            return String.format("%-4s  QPS=%,d  p50=%.3fms  p99=%.3fms  p999=%.3fms  max=%.3fms  (%.2fs)",
                    op, qps, ms(pct(0.50)), ms(pct(0.99)), ms(pct(0.999)), ms(sorted[sorted.length - 1]), secs);
        }

        long pct(double q) {
            int idx = (int) Math.min(sorted.length - 1L, Math.round(q * (sorted.length - 1)));
            return sorted[idx];
        }

        static double ms(long nanos) {
            return nanos / 1e6;
        }
    }

    private static String set(String k, String v) {
        return cmd("SET", k, v);
    }

    private static String get(String k) {
        return cmd("GET", k);
    }

    private static String cmd(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    private static int intArg(String[] a, int i, int def) {
        return a.length > i ? Integer.parseInt(a[i]) : def;
    }

    /** 요청→응답 한 개를 왕복하는 최소 연결(읽기 버퍼링). */
    private static final class Conn implements AutoCloseable {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Conn(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = socket.getOutputStream();
        }

        void roundtrip(String raw) throws IOException {
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            readReply();
        }

        private void readReply() throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new IOException("EOF");
            }
            String head = readLine();
            char t = (char) type;
            if (t == '$') {
                int len = Integer.parseInt(head.trim());
                if (len >= 0) {
                    in.readNBytes(len);
                    in.read();
                    in.read();
                }
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
