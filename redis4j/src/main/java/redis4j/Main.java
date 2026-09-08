package redis4j;

import redis4j.server.RedisServer;

import java.io.IOException;
import java.nio.file.Path;

/** redis4j 진입점. 사용법: {@code Main [port]} (기본 6379). 기동 시 {@code dump.rdb4j} 있으면 복원. */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? java.lang.Integer.parseInt(args[0]) : 6379;
        RedisServer server = new RedisServer(port, Path.of("dump.rdb4j"));
        int bound = server.start();
        System.out.println("redis4j listening on port " + bound);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "redis4j-shutdown"));
        // accept 스레드(non-daemon)가 JVM을 살려둔다. 종료는 SIGINT → shutdown hook.
    }
}
