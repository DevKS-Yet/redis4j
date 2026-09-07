package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.store.Database;
import redis4j.store.RedisObject;
import redis4j.store.RedisType;
import redis4j.store.StringObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * String 자료형 + 키 공간 공용 명령. 모든 메서드는 CommandDispatcher 가 {@code synchronized(db)} 로
 * 감싸 호출하므로 명령 단위로 원자적이다.
 */
public final class StringCommands {

    private static final String WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final String NOT_INT = "ERR value is not an integer or out of range";

    private final Database db;

    public StringCommands(Database db) {
        this.db = db;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "SET" -> set(a);
            case "GET" -> get(a);
            case "GETSET" -> getset(a);
            case "GETDEL" -> getdel(a);
            case "APPEND" -> append(a);
            case "STRLEN" -> strlen(a);
            case "SETNX" -> setnx(a);
            case "MSET" -> mset(a);
            case "MSETNX" -> msetnx(a);
            case "MGET" -> mget(a);
            case "INCR" -> incr(a, 1L);
            case "DECR" -> incr(a, -1L);
            case "INCRBY" -> incrby(a, 1);
            case "DECRBY" -> incrby(a, -1);
            case "INCRBYFLOAT" -> incrbyfloat(a);
            case "DEL" -> del(a);
            case "EXISTS" -> exists(a);
            case "TYPE" -> type(a);
            default -> null;                                 // 미처리 → 디스패처가 다음 핸들러로
        };
    }

    // ---- String ----

    private Reply set(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("set");
        }
        String key = str(a.get(1));
        byte[] value = a.get(2);
        boolean nx = false, xx = false, keepttl = false, getOpt = false;
        Long expireAt = null;
        for (int i = 3; i < a.size(); i++) {
            String opt = str(a.get(i)).toUpperCase(Locale.ROOT);
            switch (opt) {
                case "NX" -> nx = true;
                case "XX" -> xx = true;
                case "KEEPTTL" -> keepttl = true;
                case "GET" -> getOpt = true;
                case "EX", "PX", "EXAT", "PXAT" -> {
                    if (i + 1 >= a.size()) {
                        return Reply.error("ERR syntax error");
                    }
                    long n;
                    try {
                        n = Long.parseLong(str(a.get(++i)));
                    } catch (NumberFormatException e) {
                        return Reply.error(NOT_INT);
                    }
                    long now = System.currentTimeMillis();
                    expireAt = switch (opt) {
                        case "EX" -> now + n * 1000L;
                        case "PX" -> now + n;
                        case "EXAT" -> n * 1000L;
                        default -> n;               // PXAT
                    };
                }
                default -> {
                    return Reply.error("ERR syntax error");
                }
            }
        }
        if ((nx && xx) || (keepttl && expireAt != null)) {
            return Reply.error("ERR syntax error");
        }

        RedisObject old = db.get(key);
        Reply getReply = null;
        if (getOpt) {
            if (old != null && old.type() != RedisType.STRING) {
                return Reply.error(WRONGTYPE);
            }
            getReply = (old == null) ? new Reply.Nil() : Reply.bulk(((StringObject) old).value());
        }
        boolean present = old != null;
        if ((nx && present) || (xx && !present)) {
            return getOpt ? getReply : new Reply.Nil();
        }

        StringObject obj = new StringObject(value);
        if (keepttl) {
            db.putKeepTtl(key, obj);
        } else {
            db.put(key, obj, expireAt == null ? 0L : expireAt);
        }
        return getOpt ? getReply : Reply.ok();
    }

    private Reply get(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("get");
        }
        RedisObject o = db.get(str(a.get(1)));
        if (o == null) {
            return new Reply.Nil();
        }
        if (o.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        return Reply.bulk(((StringObject) o).value());
    }

    private Reply getset(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("getset");
        }
        String key = str(a.get(1));
        RedisObject old = db.get(key);
        if (old != null && old.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        db.put(key, new StringObject(a.get(2)), 0L);         // GETSET 은 TTL 제거
        return old == null ? new Reply.Nil() : Reply.bulk(((StringObject) old).value());
    }

    private Reply getdel(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("getdel");
        }
        String key = str(a.get(1));
        RedisObject old = db.get(key);
        if (old == null) {
            return new Reply.Nil();
        }
        if (old.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        db.delete(key);
        return Reply.bulk(((StringObject) old).value());
    }

    private Reply append(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("append");
        }
        String key = str(a.get(1));
        RedisObject old = db.get(key);
        if (old != null && old.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        byte[] cur = old == null ? new byte[0] : ((StringObject) old).value();
        byte[] add = a.get(2);
        byte[] merged = new byte[cur.length + add.length];
        System.arraycopy(cur, 0, merged, 0, cur.length);
        System.arraycopy(add, 0, merged, cur.length, add.length);
        db.putKeepTtl(key, new StringObject(merged));
        return new Reply.Integer(merged.length);
    }

    private Reply strlen(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("strlen");
        }
        RedisObject o = db.get(str(a.get(1)));
        if (o == null) {
            return new Reply.Integer(0);
        }
        if (o.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        return new Reply.Integer(((StringObject) o).value().length);
    }

    private Reply setnx(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("setnx");
        }
        String key = str(a.get(1));
        if (db.exists(key)) {
            return new Reply.Integer(0);
        }
        db.put(key, new StringObject(a.get(2)), 0L);
        return new Reply.Integer(1);
    }

    private Reply mset(List<byte[]> a) {
        if (a.size() < 3 || (a.size() - 1) % 2 != 0) {
            return arity("mset");
        }
        for (int i = 1; i < a.size(); i += 2) {
            db.put(str(a.get(i)), new StringObject(a.get(i + 1)), 0L);
        }
        return Reply.ok();
    }

    private Reply msetnx(List<byte[]> a) {
        if (a.size() < 3 || (a.size() - 1) % 2 != 0) {
            return arity("msetnx");
        }
        for (int i = 1; i < a.size(); i += 2) {
            if (db.exists(str(a.get(i)))) {
                return new Reply.Integer(0);
            }
        }
        for (int i = 1; i < a.size(); i += 2) {
            db.put(str(a.get(i)), new StringObject(a.get(i + 1)), 0L);
        }
        return new Reply.Integer(1);
    }

    private Reply mget(List<byte[]> a) {
        if (a.size() < 2) {
            return arity("mget");
        }
        List<Reply> out = new ArrayList<>(a.size() - 1);
        for (int i = 1; i < a.size(); i++) {
            RedisObject o = db.get(str(a.get(i)));
            if (o != null && o.type() == RedisType.STRING) {
                out.add(Reply.bulk(((StringObject) o).value()));
            } else {
                out.add(new Reply.Nil());                    // 없거나 타입 불일치 → nil (MGET 은 에러 안 냄)
            }
        }
        return new Reply.Array(out);
    }

    // ---- 수치 ----

    private Reply incr(List<byte[]> a, long by) {
        if (a.size() != 2) {
            return arity(by > 0 ? "incr" : "decr");
        }
        return doIncr(str(a.get(1)), by);
    }

    private Reply incrby(List<byte[]> a, int sign) {
        if (a.size() != 3) {
            return arity(sign > 0 ? "incrby" : "decrby");
        }
        long delta;
        try {
            delta = Long.parseLong(str(a.get(2)));
        } catch (NumberFormatException e) {
            return Reply.error(NOT_INT);
        }
        long by;
        try {
            by = sign > 0 ? delta : Math.negateExact(delta);
        } catch (ArithmeticException e) {
            return Reply.error("ERR increment or decrement would overflow");
        }
        return doIncr(str(a.get(1)), by);
    }

    private Reply doIncr(String key, long by) {
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        long cur = 0;
        if (o != null) {
            try {
                cur = Long.parseLong(new String(((StringObject) o).value(), StandardCharsets.UTF_8));
            } catch (NumberFormatException e) {
                return Reply.error(NOT_INT);
            }
        }
        long result;
        try {
            result = Math.addExact(cur, by);
        } catch (ArithmeticException e) {
            return Reply.error("ERR increment or decrement would overflow");
        }
        db.putKeepTtl(key, StringObject.of(Long.toString(result)));
        return new Reply.Integer(result);
    }

    private Reply incrbyfloat(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("incrbyfloat");
        }
        String key = str(a.get(1));
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.STRING) {
            return Reply.error(WRONGTYPE);
        }
        BigDecimal cur;
        BigDecimal inc;
        try {
            cur = o == null ? BigDecimal.ZERO
                    : new BigDecimal(new String(((StringObject) o).value(), StandardCharsets.UTF_8).trim());
            inc = new BigDecimal(str(a.get(2)).trim());
        } catch (NumberFormatException e) {
            return Reply.error("ERR value is not a valid float");
        }
        BigDecimal result = cur.add(inc);
        String out = result.stripTrailingZeros().toPlainString();
        db.putKeepTtl(key, StringObject.of(out));
        return Reply.bulk(out);
    }

    // ---- 키 공간 공용 ----

    private Reply del(List<byte[]> a) {
        if (a.size() < 2) {
            return arity("del");
        }
        int removed = 0;
        for (int i = 1; i < a.size(); i++) {
            if (db.delete(str(a.get(i)))) {
                removed++;
            }
        }
        return new Reply.Integer(removed);
    }

    private Reply exists(List<byte[]> a) {
        if (a.size() < 2) {
            return arity("exists");
        }
        int count = 0;
        for (int i = 1; i < a.size(); i++) {
            if (db.exists(str(a.get(i)))) {
                count++;
            }
        }
        return new Reply.Integer(count);
    }

    private Reply type(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("type");
        }
        RedisType t = db.typeOf(str(a.get(1)));
        return new Reply.Simple(t == null ? "none" : t.typeName());
    }

    // ---- helpers ----

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
