package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.pubsub.PubSub;
import redis4j.server.ConnectionState;
import redis4j.store.Database;
import redis4j.store.Keyspace;
import redis4j.tx.Transactions;
import redis4j.tx.TxState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
    private final Transactions transactions = new Transactions();

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
        switch (name) {                                         // 트랜잭션 제어(큐잉 대상 아님)
            case "MULTI":
                return multi(state);
            case "DISCARD":
                return discard(state);
            case "WATCH":
                return watch(args, state);
            case "UNWATCH":
                state.tx().unwatch();
                return Reply.ok();
            case "EXEC":
                return exec(state);
            default:
                break;
        }
        if (state.tx().inMulti()) {                             // MULTI 중이면 큐에 적재(+QUEUED)
            return queue(name, args, state);
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
                    return executeData(name, args, state);
                }
        }
    }

    /** 자료형·키공간 명령 실행(호출자가 {@code synchronized(ks)} 보유). WATCH 버전 증가 포함. */
    private Reply executeData(String name, List<byte[]> args, ConnectionState state) {
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
        bumpWatchVersions(name, args, state);
        return r;
    }

    /** 쓰기/대량변경 시 WATCH 버전·epoch 를 올린다(추적 중인 키만 실제 증가). */
    private void bumpWatchVersions(String name, List<byte[]> args, ConnectionState state) {
        if (CommandCatalog.isWrite(name)) {
            int db = state.dbIndex();
            for (String key : CommandCatalog.writtenKeys(name, args)) {
                transactions.bump(db, key);
            }
        } else if (name.equals("FLUSHDB") || name.equals("FLUSHALL") || name.equals("SWAPDB")) {
            transactions.bumpEpoch();
        }
    }

    private Reply multi(ConnectionState state) {
        if (state.tx().inMulti()) {
            return Reply.error("ERR MULTI calls can not be nested");
        }
        state.tx().beginMulti();
        return Reply.ok();
    }

    private Reply discard(ConnectionState state) {
        if (!state.tx().inMulti()) {
            return Reply.error("ERR DISCARD without MULTI");
        }
        state.tx().endMulti();
        state.tx().unwatch();
        return Reply.ok();
    }

    private Reply watch(List<byte[]> args, ConnectionState state) {
        if (args.size() < 2) {
            return Reply.error("ERR wrong number of arguments for 'watch' command");
        }
        if (state.tx().inMulti()) {
            return Reply.error("ERR WATCH inside MULTI is not allowed");
        }
        synchronized (ks) {
            int db = state.dbIndex();
            for (int i = 1; i < args.size(); i++) {
                String key = new String(args.get(i), StandardCharsets.UTF_8);
                long v = transactions.track(db, key);
                state.tx().watch(db, key, v, transactions.epoch());
            }
        }
        return Reply.ok();
    }

    private Reply queue(String name, List<byte[]> args, ConnectionState state) {
        if (isSubscribeFamily(name)) {                          // (P)SUBSCRIBE 계열은 MULTI 안에서 불가
            state.tx().markQueueError();
            return Reply.error("ERR " + name + " is not allowed in transactions");
        }
        if (!CommandCatalog.isKnown(name)) {                    // 미지 명령 → EXECABORT 예약
            state.tx().markQueueError();
            return Reply.error("ERR unknown command '"
                    + new String(args.get(0), StandardCharsets.UTF_8) + "'");
        }
        state.tx().enqueue(args);
        return new Reply.Simple("QUEUED");
    }

    private Reply exec(ConnectionState state) {
        TxState tx = state.tx();
        if (!tx.inMulti()) {
            return Reply.error("ERR EXEC without MULTI");
        }
        if (tx.queueError()) {                                  // 큐잉 중 오류 → 트랜잭션 폐기
            tx.endMulti();
            tx.unwatch();
            return Reply.error("EXECABORT Transaction discarded because of previous errors.");
        }
        synchronized (ks) {
            if (watchDirty(state)) {                            // 감시 키 변경 → 취소(null array)
                tx.endMulti();
                tx.unwatch();
                return new Reply.NilArray();
            }
            List<Reply> results = new ArrayList<>(tx.queued().size());
            for (List<byte[]> cmd : tx.queued()) {
                String cn = new String(cmd.get(0), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
                results.add(executeData(cn, cmd, state));       // 런타임 오류는 해당 원소만 오류(롤백 없음)
            }
            tx.endMulti();
            tx.unwatch();
            return new Reply.Array(results);
        }
    }

    private boolean watchDirty(ConnectionState state) {
        TxState tx = state.tx();
        if (!tx.watching()) {
            return false;
        }
        if (transactions.epoch() != tx.watchedEpoch()) {
            return true;
        }
        for (TxState.Watch w : tx.watched()) {
            if (transactions.version(w.db(), w.key()) != w.version()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSubscribeFamily(String name) {
        return switch (name) {
            case "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE" -> true;
            default -> false;
        };
    }
}
