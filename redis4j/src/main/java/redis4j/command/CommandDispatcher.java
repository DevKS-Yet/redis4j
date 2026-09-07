package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.server.ConnectionState;
import redis4j.store.Database;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 명령명을 처리기로 라우팅한다. 연결 수준 명령(PING/ECHO/COMMAND/QUIT)은 직접 처리하고,
 * 데이터 명령은 {@code synchronized(db)} 로 직렬화해 자료형별 핸들러에 위임한다
 * (명령 단위 원자성 — Redis 단일 스레드 실행 모델). 인자는 바이트 안전한 {@code byte[]}.
 *
 * <p>데이터 명령은 각 핸들러가 미처리 시 null 을 반환하고, 순서대로 시도한 뒤 모두 null 이면
 * unknown-command 에러를 낸다.
 */
public final class CommandDispatcher {

    private final Database db;
    private final StringCommands strings;
    private final ExpireCommands expire;
    private final ListCommands lists;
    private final HashCommands hashes;
    private final SetCommands sets;

    public CommandDispatcher(Database db) {
        this.db = db;
        this.strings = new StringCommands(db);
        this.expire = new ExpireCommands(db);
        this.lists = new ListCommands(db);
        this.hashes = new HashCommands(db);
        this.sets = new SetCommands(db);
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
                    Reply r = strings.execute(name, args);
                    if (r == null) {
                        r = expire.execute(name, args);
                    }
                    if (r == null) {
                        r = lists.execute(name, args);
                    }
                    if (r == null) {
                        r = hashes.execute(name, args);
                    }
                    if (r == null) {
                        r = sets.execute(name, args);
                    }
                    if (r == null) {
                        r = Reply.error("ERR unknown command '"
                                + new String(args.get(0), StandardCharsets.UTF_8) + "'");
                    }
                    return r;
                }
        }
    }
}
