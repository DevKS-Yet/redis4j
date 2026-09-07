package redis4j.server;

import redis4j.command.CommandDispatcher;
import redis4j.store.Database;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
    private final Database database = new Database();
    private final CommandDispatcher dispatcher = new CommandDispatcher(database);
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

    public RedisServer(int port) {
        this.port = port;
    }

    /** 이 서버의 키 공간(테스트·검증에서 상태 주입/확인용). */
    public Database database() {
        return database;
    }

    /** 소켓을 바인딩하고 accept 루프를 시작한 뒤, 실제 리슨 포트를 반환한다(포트 0이면 임의 포트). */
    public int start() throws IOException {
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

    /** 능동 만료 한 사이클(사이클당 최대 100개 검사 — 부하 제한). */
    private void activeExpire() {
        synchronized (database) {
            database.activeExpireCycle(100);
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
    }
}
