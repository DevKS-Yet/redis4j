package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.pubsub.PubSub;
import redis4j.server.ConnectionState;
import redis4j.store.Database;
import redis4j.store.Keyspace;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 명령명을 처리기로 라우팅한다. 연결 수준 명령(PING/ECHO/COMMAND/QUIT)은 직접 처리하고,
 * 데이터 명령은 {@code synchronized(keyspace)} 로 직렬화해 자료형별 핸들러에 위임한다
 * (명령 단위 원자성 — Redis 단일 스레드 실행 모델). 인자는 바이트 안전한 {@code byte[]}.
 *
 * <p>연결이 SELECT 한 현재 DB 로 라우팅한다. 자료형 핸들러는 DB별로 미리 구성해 두고
 * (SWAPDB 는 참조가 아닌 DB 내용을 교환하므로 바인딩이 그대로 유효), 미처리 시 순서대로
 * 다음 핸들러·키공간 핸들러를 시도한 뒤 모두 null 이면 unknown-command 에러를 낸다.
 */
public final class CommandDispatcher {

    private final Keyspace ks;
    private final Handlers[] handlers;
    private final KeyspaceCommands keyspace;
    private final PubSub pubSub = new PubSub();
    private final PubSubCommands pubSubCommands = new PubSubCommands(pubSub);

    public CommandDispatcher(Keyspace ks) {
        this.ks = ks;
        this.handlers = new Handlers[ks.count()];
        for (int i = 0; i < ks.count(); i++) {
            this.handlers[i] = new Handlers(ks.db(i));
        }
        this.keyspace = new KeyspaceCommands(ks);
    }

    /** 연결 종료 시 정리 — 남은 구독을 레지스트리에서 제거(자동 구독 해제). */
    public void onDisconnect(ConnectionState state) {
        if (state.subscriber() != null) {
            pubSub.removeAll(state.subscriber());
        }
    }

    private static boolean allowedInSubscribe(String name) {
        return switch (name) {
            case "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PING", "QUIT" -> true;
            default -> false;
        };
    }

    /** 한 논리 DB에 바인딩된 자료형 명령 핸들러 묶음. */
    private static final class Handlers {
        final StringCommands strings;
        final ExpireCommands expire;
        final ListCommands lists;
        final HashCommands hashes;
        final SetCommands sets;
        final ZSetCommands zsets;

        Handlers(Database db) {
            this.strings = new StringCommands(db);
            this.expire = new ExpireCommands(db);
            this.lists = new ListCommands(db);
            this.hashes = new HashCommands(db);
            this.sets = new SetCommands(db);
            this.zsets = new ZSetCommands(db);
        }
    }

    public Reply dispatch(List<byte[]> args, ConnectionState state) {
        if (args == null || args.isEmpty()) {
            return Reply.error("ERR empty command");
        }
        String name = new String(args.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
        if (state.isSubscribed() && !allowedInSubscribe(name)) {
            return Reply.error("ERR Can't execute '" + name.toLowerCase(Locale.ROOT)
                    + "': only (P)SUBSCRIBE / (P)UNSUBSCRIBE / PING / QUIT are allowed in this context");
        }
        switch (name) {
            case "PING":
                if (state.isSubscribed()) {                     // 구독 모드: 배열 형태 pong
                    Reply payload = args.size() > 1 ? Reply.bulk(args.get(1)) : Reply.bulk("");
                    return new Reply.Array(List.of(Reply.bulk("pong"), payload));
                }
                return args.size() > 1 ? Reply.bulk(args.get(1)) : Reply.pong();
            case "ECHO":
                return args.size() == 2 ? Reply.bulk(args.get(1))
                        : Reply.error("ERR wrong number of arguments for 'echo' command");
            case "COMMAND":
                return new Reply.Array(List.of());
            case "QUIT":
                state.setQuit(true);
                return Reply.ok();
            case "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PUBLISH", "PUBSUB":
                return pubSubCommands.execute(name, args, state);   // 구독 확인·메시지는 자체 전송(null 가능)
            default:
                synchronized (ks) {
                    Handlers h = handlers[state.dbIndex()];
                    Reply r = h.strings.execute(name, args);
                    if (r == null) {
                        r = h.expire.execute(name, args);
                    }
                    if (r == null) {
                        r = h.lists.execute(name, args);
                    }
                    if (r == null) {
                        r = h.hashes.execute(name, args);
                    }
                    if (r == null) {
                        r = h.sets.execute(name, args);
                    }
                    if (r == null) {
                        r = h.zsets.execute(name, args);
                    }
                    if (r == null) {
                        r = keyspace.execute(name, args, state);
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
