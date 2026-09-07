package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.store.Database;
import redis4j.store.HashObject;
import redis4j.store.RedisObject;
import redis4j.store.RedisType;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Hash 자료형 명령. CommandDispatcher 가 {@code synchronized(db)} 로 감싸 호출한다. 미처리 시 null.
 */
public final class HashCommands {

    private static final String WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final String NOT_INT = "ERR value is not an integer or out of range";

    private final Database db;

    public HashCommands(Database db) {
        this.db = db;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "HSET" -> hset(a, false);
            case "HMSET" -> hset(a, true);
            case "HSETNX" -> hsetnx(a);
            case "HGET" -> hget(a);
            case "HMGET" -> hmget(a);
            case "HGETALL" -> hgetall(a);
            case "HDEL" -> hdel(a);
            case "HEXISTS" -> hexists(a);
            case "HLEN" -> hlen(a);
            case "HKEYS" -> hkeys(a);
            case "HVALS" -> hvals(a);
            case "HSTRLEN" -> hstrlen(a);
            case "HINCRBY" -> hincrby(a);
            case "HINCRBYFLOAT" -> hincrbyfloat(a);
            default -> null;
        };
    }

    /** 존재하면 HashObject, 없으면 null. 타입 불일치면 예외 대신 호출부에서 db.get 재확인 없이 판정하도록 별도 검사. */
    private HashObject hashAt(String key) {
        RedisObject o = db.get(key);
        return (o instanceof HashObject h) ? h : null;
    }

    private boolean wrongType(String key) {
        RedisObject o = db.get(key);
        return o != null && o.type() != RedisType.HASH;
    }

    private Reply hset(List<byte[]> a, boolean legacyOk) {
        String cmd = legacyOk ? "hmset" : "hset";
        if (a.size() < 4 || (a.size() - 2) % 2 != 0) {
            return arity(cmd);
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            h = new HashObject();
            db.put(key, h, 0L);
        }
        int added = 0;
        for (int i = 2; i < a.size(); i += 2) {
            String field = str(a.get(i));
            if (!h.fields().containsKey(field)) {
                added++;
            }
            h.fields().put(field, a.get(i + 1));
        }
        return legacyOk ? Reply.ok() : new Reply.Integer(added);
    }

    private Reply hsetnx(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("hsetnx");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        String field = str(a.get(2));
        if (h != null && h.fields().containsKey(field)) {
            return new Reply.Integer(0);
        }
        if (h == null) {
            h = new HashObject();
            db.put(key, h, 0L);
        }
        h.fields().put(field, a.get(3));
        return new Reply.Integer(1);
    }

    private Reply hget(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("hget");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        byte[] v = h == null ? null : h.fields().get(str(a.get(2)));
        return v == null ? new Reply.Nil() : Reply.bulk(v);
    }

    private Reply hmget(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("hmget");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        List<Reply> out = new ArrayList<>(a.size() - 2);
        for (int i = 2; i < a.size(); i++) {
            byte[] v = h == null ? null : h.fields().get(str(a.get(i)));
            out.add(v == null ? new Reply.Nil() : Reply.bulk(v));
        }
        return new Reply.Array(out);
    }

    private Reply hgetall(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("hgetall");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            return new Reply.Array(List.of());
        }
        List<Reply> out = new ArrayList<>(h.fields().size() * 2);
        for (Map.Entry<String, byte[]> e : h.fields().entrySet()) {
            out.add(Reply.bulk(e.getKey()));
            out.add(Reply.bulk(e.getValue()));
        }
        return new Reply.Array(out);
    }

    private Reply hdel(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("hdel");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            return new Reply.Integer(0);
        }
        int removed = 0;
        for (int i = 2; i < a.size(); i++) {
            if (h.fields().remove(str(a.get(i))) != null) {
                removed++;
            }
        }
        if (h.fields().isEmpty()) {
            db.delete(key);
        }
        return new Reply.Integer(removed);
    }

    private Reply hexists(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("hexists");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        return new Reply.Integer(h != null && h.fields().containsKey(str(a.get(2))) ? 1 : 0);
    }

    private Reply hlen(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("hlen");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        return new Reply.Integer(h == null ? 0 : h.fields().size());
    }

    private Reply hkeys(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("hkeys");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            return new Reply.Array(List.of());
        }
        List<Reply> out = new ArrayList<>(h.fields().size());
        for (String f : h.fields().keySet()) {
            out.add(Reply.bulk(f));
        }
        return new Reply.Array(out);
    }

    private Reply hvals(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("hvals");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            return new Reply.Array(List.of());
        }
        List<Reply> out = new ArrayList<>(h.fields().size());
        for (byte[] v : h.fields().values()) {
            out.add(Reply.bulk(v));
        }
        return new Reply.Array(out);
    }

    private Reply hstrlen(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("hstrlen");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        byte[] v = h == null ? null : h.fields().get(str(a.get(2)));
        return new Reply.Integer(v == null ? 0 : v.length);
    }

    private Reply hincrby(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("hincrby");
        }
        long by;
        try {
            by = Long.parseLong(str(a.get(3)));
        } catch (NumberFormatException e) {
            return Reply.error(NOT_INT);
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            h = new HashObject();
            db.put(key, h, 0L);
        }
        String field = str(a.get(2));
        byte[] cur = h.fields().get(field);
        long base = 0;
        if (cur != null) {
            try {
                base = Long.parseLong(new String(cur, StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                return Reply.error("ERR hash value is not an integer");
            }
        }
        long result;
        try {
            result = Math.addExact(base, by);
        } catch (ArithmeticException e) {
            return Reply.error("ERR increment or decrement would overflow");
        }
        h.fields().put(field, Long.toString(result).getBytes(StandardCharsets.UTF_8));
        return new Reply.Integer(result);
    }

    private Reply hincrbyfloat(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("hincrbyfloat");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        HashObject h = hashAt(key);
        if (h == null) {
            h = new HashObject();
            db.put(key, h, 0L);
        }
        String field = str(a.get(2));
        byte[] cur = h.fields().get(field);
        BigDecimal base;
        BigDecimal inc;
        try {
            base = cur == null ? BigDecimal.ZERO
                    : new BigDecimal(new String(cur, StandardCharsets.UTF_8).trim());
            inc = new BigDecimal(str(a.get(3)).trim());
        } catch (NumberFormatException e) {
            return Reply.error("ERR hash value is not a float");
        }
        String out = base.add(inc).stripTrailingZeros().toPlainString();
        h.fields().put(field, out.getBytes(StandardCharsets.UTF_8));
        return Reply.bulk(out);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
