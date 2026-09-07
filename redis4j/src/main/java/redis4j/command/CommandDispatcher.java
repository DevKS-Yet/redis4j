package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.server.ConnectionState;
import redis4j.store.Database;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 명령명을 처리기로 라우팅한다. 연결 수준 명령(PING/ECHO/COMMAND/QUIT)은 직접 처리하고,
 * 데이터 명령은 {@code synchronized(db)} 로 직렬화해 {@link StringCommands} 에 위임한다
 * (명령 단위 원자성 — Redis 단일 스레드 실행 모델). 인자는 바이트 안전한 {@code byte[]}.
 */
public final class CommandDispatcher {

    private final Database db;
    private final StringCommands strings;

    public CommandDispatcher(Database db) {
        this.db = db;
        this.strings = new StringCommands(db);
    }

    public Reply dispatch(List<byte[]> args, ConnectionState state) {
        if (args == null || args.isEmpty()) {
            return Reply.error("ERR empty command");
        }
        String name = new String(args.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        switch (name) {
            case "PING":
                return args.size() > 1 ? Reply.bulk(args.get(1)) : Reply.pong();
            case "ECHO":
                return args.size() == 2 ? Reply.bulk(args.get(1))
                        : Reply.error("ERR wrong number of arguments for 'echo' command");
            case "COMMAND":
                return new Reply.Array(List.of());
            case "QUIT":
                state.setQuit(true);
                return Reply.ok();
            default:
                synchronized (db) {
                    return strings.execute(name, args);
                }
        }
    }
}
