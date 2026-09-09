package redis4j.server;

import redis4j.command.CommandDispatcher;
import redis4j.persistence.aof.AofManager;
import redis4j.persistence.rdb.RdbManager;
import redis4j.store.Database;
import redis4j.store.Keyspace;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RESP2 TCP 서버. accept 루프는 전용(non-daemon) 플랫폼 스레드에서 돌고,
 * 각 연결은 가상 스레드(thread-per-connection)에서 처리한다.
 */
public final class RedisServer implements AutoCloseable {

    private final int port;
    private final Keyspace keyspace = new Keyspace();
    private final RdbManager rdb;
    private final AofManager aof;
    private final boolean autoLoad;
    private final CommandDispatcher dispatcher;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis4j-expiry");
                t.setDaemon(true);
                return t;
            });

    private static final int DEFAULT_MAX_CLIENTS = 10_000;
    private volatile int maxClients = DEFAULT_MAX_CLIENTS;   // 연결 자원 상한(NFR)
    private final AtomicInteger activeClients = new AtomicInteger();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** 기본 생성자. 영속화 기본값(자동 로드 안 함) — 테스트 격리를 위함. */
    public RedisServer(int port) {
        this(port, PersistenceOptions.defaults());
    }

    /** RDB 스냅샷 경로를 지정하고 기동 시 자동 로드한다(RDB 전용). */
    public RedisServer(int port, Path rdbPath) {
        this(port, PersistenceOptions.rdb(rdbPath));
    }

    /** 영속화 설정을 지정한다(RDB + AOF). */
    public RedisServer(int port, PersistenceOptions options) {
        this.port = port;
        this.rdb = new RdbManager(keyspace, options.rdbPath());
        this.aof = new AofManager(keyspace, options.aofPath(), options.appendOnly(),
                AofManager.parseFsync(options.fsyncPolicy()));
        this.autoLoad = options.autoLoad();
        this.dispatcher = new CommandDispatcher(keyspace, rdb, aof);
    }

    /** 전역 실행 락이자 다중 논리 DB 컨테이너(테스트·검증에서 락·확인용). */
    public Keyspace keyspace() {
        return keyspace;
    }

    /** 기본 논리 DB(0). 테스트·검증에서 상태 주입/확인용. */
    public Database database() {
        return keyspace.db(0);
    }

    /** 현재 활성 연결 수(검증·모니터링용). */
    public int activeClients() {
        return activeClients.get();
    }

    /** 연결 수 상한 설정(검증용). start() 전/후 모두 반영. */
    public void setMaxClients(int n) {
        this.maxClients = n;
    }

    /** 소켓을 바인딩하고 accept 루프를 시작한 뒤, 실제 리슨 포트를 반환한다(포트 0이면 임의 포트). */
    public int start() throws IOException {
        if (autoLoad) {                                         // accept·만료 스케줄러 전에 복원
            if (aof.isEnabled() && aof.exists()) {
                dispatcher.loadFrom(aof.readAll());             // AOF 우선(RDB보다)
            } else {
                rdb.loadIfExists();
            }
        }
        if (aof.isEnabled()) {
            aof.open();                                         // 재생 후 이어쓰기 시작
        }
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        int bound = serverSocket.getLocalPort();
        acceptThread = Thread.ofPlatform().name("redis4j-accept").start(this::acceptLoop);
        expiryScheduler.scheduleAtFixedRate(this::activeExpire, 100, 100, TimeUnit.MILLISECONDS);
        return bound;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                if (activeClients.incrementAndGet() > maxClients) {  // 연결 자원 상한 초과
                    activeClients.decrementAndGet();
                    rejectExcess(client);
                    continue;
                }
                connections.submit(() -> {
                    try {
                        new ConnectionHandler(client, dispatcher).run();
                    } finally {
                        activeClients.decrementAndGet();             // 연결 정리 시 카운트 반환
                    }
                });
            } catch (IOException e) {
                if (running) {
                    System.err.println("accept 실패: " + e.getMessage());
                } else {
                    break;                                  // close()에 의한 정상 종료
                }
            }
        }
    }

    /** 연결 상한 초과 시 표준 에러를 보내고 소켓을 닫는다(Redis 규약). */
    private void rejectExcess(Socket client) {
        try (Socket s = client) {
            OutputStream out = s.getOutputStream();
            out.write("-ERR max number of clients reached\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException ignored) {
            // 거부 중 쓰기 실패는 무시 — 어차피 닫는다.
        }
    }

    /** 능동 만료 한 사이클(DB당 최대 100개 검사 — 부하 제한). 전역 락으로 명령과 직렬화. */
    private void activeExpire() {
        synchronized (keyspace) {
            keyspace.activeExpireCycle(100);
        }
    }

    /** 리슨 소켓·연결 스레드·만료 스케줄러를 정리한다(graceful shutdown). */
    @Override
    public void close() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();                       // accept() 깨우기
            }
        } catch (IOException ignored) {
            // 무시
        }
        connections.shutdownNow();
        expiryScheduler.shutdownNow();
        rdb.shutdown();
        aof.close();
    }
}
