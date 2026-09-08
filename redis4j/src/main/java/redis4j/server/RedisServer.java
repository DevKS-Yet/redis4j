package redis4j.server;

import redis4j.command.CommandDispatcher;
import redis4j.persistence.rdb.RdbManager;
import redis4j.store.Database;
import redis4j.store.Keyspace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RESP2 TCP 서버. accept 루프는 전용(non-daemon) 플랫폼 스레드에서 돌고,
 * 각 연결은 가상 스레드(thread-per-connection)에서 처리한다.
 */
public final class RedisServer implements AutoCloseable {

    private final int port;
    private final Keyspace keyspace = new Keyspace();
    private final RdbManager rdb;
    private final boolean autoLoad;
    private final CommandDispatcher dispatcher;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService expiryScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis4j-expiry");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** 기본 생성자. 스냅샷 경로는 {@code dump.rdb4j}(SAVE 대상) 이나 기동 시 자동 로드는 하지 않는다
     *  — 테스트 격리를 위함. 실 서버는 {@link #RedisServer(int, Path)} 로 자동 로드를 켠다. */
    public RedisServer(int port) {
        this(port, Path.of("dump.rdb4j"), false);
    }

    /** 스냅샷 경로를 지정하고 기동 시 해당 파일이 있으면 자동 로드한다. */
    public RedisServer(int port, Path rdbPath) {
        this(port, rdbPath, true);
    }

    private RedisServer(int port, Path rdbPath, boolean autoLoad) {
        this.port = port;
        this.rdb = new RdbManager(keyspace, rdbPath);
        this.autoLoad = autoLoad;
        this.dispatcher = new CommandDispatcher(keyspace, rdb);
    }

    /** 전역 실행 락이자 다중 논리 DB 컨테이너(테스트·검증에서 락·확인용). */
    public Keyspace keyspace() {
        return keyspace;
    }

    /** 기본 논리 DB(0). 테스트·검증에서 상태 주입/확인용. */
    public Database database() {
        return keyspace.db(0);
    }

    /** 소켓을 바인딩하고 accept 루프를 시작한 뒤, 실제 리슨 포트를 반환한다(포트 0이면 임의 포트). */
    public int start() throws IOException {
        if (autoLoad) {
            rdb.loadIfExists();                                 // accept·만료 스케줄러 전에 복원
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
                connections.submit(new ConnectionHandler(client, dispatcher));
            } catch (IOException e) {
                if (running) {
                    System.err.println("accept 실패: " + e.getMessage());
                } else {
                    break;                                  // close()에 의한 정상 종료
                }
            }
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
    }
}
