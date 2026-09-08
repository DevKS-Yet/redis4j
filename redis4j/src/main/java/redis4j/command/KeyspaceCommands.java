package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.server.ConnectionState;
import redis4j.store.Database;
import redis4j.store.Keyspace;
import redis4j.store.RedisType;
import redis4j.util.GlobMatcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 키 공간 관리·조회 및 다중 논리 DB 명령. CommandDispatcher 가 {@code synchronized(keyspace)} 로
 * 감싸 호출한다(연결의 현재 DB 기준). 미처리 시 null. DEL·EXISTS·TYPE 은 StringCommands 담당.
 */
public final class KeyspaceCommands {

    private final Keyspace ks;

    public KeyspaceCommands(Keyspace ks) {
        this.ks = ks;
    }

    public Reply execute(String name, List<byte[]> a, ConnectionState state) {
        Database db = ks.db(state.dbIndex());
        return switch (name) {
            case "KEYS" -> keys(db, a);
            case "SCAN" -> scan(db, a);
            case "RANDOMKEY" -> randomKey(db, a);
            case "DBSIZE" -> dbsize(db, a);
            case "RENAME" -> rename(db, a, false);
            case "RENAMENX" -> rename(db, a, true);
            case "FLUSHDB" -> flush(a, db, null);
            case "FLUSHALL" -> flush(a, null, ks);
            case "SELECT" -> select(a, state);
            case "SWAPDB" -> swapdb(a);
            case "UNLINK" -> unlink(db, a);
            default -> null;
        };
    }

    private Reply keys(Database db, List<byte[]> a) {
        if (a.size() != 2) {
            return arity("keys");
        }
        String pattern = str(a.get(1));
        List<Reply> out = new ArrayList<>();
        for (String k : db.liveKeys()) {
            if (GlobMatcher.matches(pattern, k)) {
                out.add(Reply.bulk(k));
            }
        }
        return new Reply.Array(out);
    }

    private Reply scan(Database db, List<byte[]> a) {
        if (a.size() < 2) {
            return arity("scan");
        }
        long cursor;
        try {
            cursor = Long.parseLong(str(a.get(1)));
        } catch (NumberFormatException e) {
            return Reply.error("ERR invalid cursor");
        }
        if (cursor < 0) {
            return Reply.error("ERR invalid cursor");
        }
        String match = null;
        int count = 10;
        String typeFilter = null;
        for (int i = 2; i < a.size(); i++) {
            String opt = str(a.get(i)).toUpperCase(Locale.ROOT);
            switch (opt) {
                case "MATCH" -> {
                    if (++i >= a.size()) {
                        return Reply.error("ERR syntax error");
                    }
                    match = str(a.get(i));
                }
                case "COUNT" -> {
                    if (++i >= a.size()) {
                        return Reply.error("ERR syntax error");
                    }
                    try {
                        count = Integer.parseInt(str(a.get(i)));
                    } catch (NumberFormatException e) {
                        return Reply.error("ERR value is not an integer or out of range");
                    }
                    if (count < 1) {
                        return Reply.error("ERR syntax error");
                    }
                }
                case "TYPE" -> {
                    if (++i >= a.size()) {
                        return Reply.error("ERR syntax error");
                    }
                    typeFilter = str(a.get(i));
                }
                default -> {
                    return Reply.error("ERR syntax error");
                }
            }
        }
        // 안정적 순회를 위해 정렬된 키에 커서=인덱스 오프셋을 적용(약한 일관성 허용).
        List<String> keys = db.liveKeys();
        Collections.sort(keys);
        int start = (int) Math.min(cursor, keys.size());
        int end = Math.min(start + count, keys.size());
        List<Reply> matched = new ArrayList<>();
        for (int i = start; i < end; i++) {
            String k = keys.get(i);
            if (match != null && !GlobMatcher.matches(match, k)) {
                continue;
            }
            if (typeFilter != null) {
                RedisType t = db.typeOf(k);
                if (t == null || !t.typeName().equalsIgnoreCase(typeFilter)) {
                    continue;
                }
            }
            matched.add(Reply.bulk(k));
        }
        long next = end >= keys.size() ? 0 : end;
        return new Reply.Array(List.of(Reply.bulk(Long.toString(next)), new Reply.Array(matched)));
    }

    private Reply randomKey(Database db, List<byte[]> a) {
        if (a.size() != 1) {
            return arity("randomkey");
        }
        String k = db.randomKey();
        return k == null ? new Reply.Nil() : Reply.bulk(k);
    }

    private Reply dbsize(Database db, List<byte[]> a) {
        if (a.size() != 1) {
            return arity("dbsize");
        }
        return new Reply.Integer(db.rawSize());
    }

    private Reply rename(Database db, List<byte[]> a, boolean nx) {
        if (a.size() != 3) {
            return arity(nx ? "renamenx" : "rename");
        }
        String src = str(a.get(1));
        String dst = str(a.get(2));
        if (!db.exists(src)) {
            return Reply.error("ERR no such key");
        }
        if (nx && db.exists(dst)) {
            return new Reply.Integer(0);
        }
        db.rename(src, dst);
        return nx ? new Reply.Integer(1) : Reply.ok();
    }

    /** FLUSHDB(db!=null) 또는 FLUSHALL(ks!=null). 선택적 ASYNC/SYNC 인자 허용(무시). */
    private Reply flush(List<byte[]> a, Database db, Keyspace all) {
        if (a.size() > 2) {
            return arity(db != null ? "flushdb" : "flushall");
        }
        if (a.size() == 2) {
            String mode = str(a.get(1)).toUpperCase(Locale.ROOT);
            if (!mode.equals("ASYNC") && !mode.equals("SYNC")) {
                return Reply.error("ERR syntax error");
            }
        }
        if (db != null) {
            db.clear();
        } else {
            all.flushAll();
        }
        return Reply.ok();
    }

    private Reply select(List<byte[]> a, ConnectionState state) {
        if (a.size() != 2) {
            return arity("select");
        }
        int idx;
        try {
            idx = Integer.parseInt(str(a.get(1)));
        } catch (NumberFormatException e) {
            return Reply.error("ERR value is not an integer or out of range");
        }
        if (idx < 0 || idx >= ks.count()) {
            return Reply.error("ERR DB index is out of range");
        }
        state.setDbIndex(idx);
        return Reply.ok();
    }

    private Reply swapdb(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("swapdb");
        }
        int i;
        int j;
        try {
            i = Integer.parseInt(str(a.get(1)));
        } catch (NumberFormatException e) {
            return Reply.error("ERR invalid first DB index");
        }
        try {
            j = Integer.parseInt(str(a.get(2)));
        } catch (NumberFormatException e) {
            return Reply.error("ERR invalid second DB index");
        }
        if (i < 0 || i >= ks.count() || j < 0 || j >= ks.count()) {
            return Reply.error("ERR DB index is out of range");
        }
        ks.swap(i, j);
        return Reply.ok();
    }

    private Reply unlink(Database db, List<byte[]> a) {
        if (a.size() < 2) {
            return arity("unlink");
        }
        int removed = 0;
        for (int i = 1; i < a.size(); i++) {
            if (db.delete(str(a.get(i)))) {
                removed++;
            }
        }
        return new Reply.Integer(removed);
    }

    // ---- helpers ----

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
