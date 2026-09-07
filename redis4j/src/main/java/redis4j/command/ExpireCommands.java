package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.store.Database;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 키 만료 명령 — EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/TTL/PTTL/PERSIST.
 * CommandDispatcher 가 {@code synchronized(db)} 로 감싸 호출한다. 처리하지 않는 명령이면 null.
 */
public final class ExpireCommands {

    private static final String NOT_INT = "ERR value is not an integer or out of range";

    private final Database db;

    public ExpireCommands(Database db) {
        this.db = db;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "EXPIRE" -> setExpire(a, 1000L, false);
            case "PEXPIRE" -> setExpire(a, 1L, false);
            case "EXPIREAT" -> setExpire(a, 1000L, true);
            case "PEXPIREAT" -> setExpire(a, 1L, true);
            case "TTL" -> ttl(a, true);
            case "PTTL" -> ttl(a, false);
            case "PERSIST" -> persist(a);
            default -> null;
        };
    }

    private Reply setExpire(List<byte[]> a, long unitMs, boolean absolute) {
        if (a.size() != 3) {
            return arity(absolute ? (unitMs == 1L ? "pexpireat" : "expireat")
                    : (unitMs == 1L ? "pexpire" : "expire"));
        }
        String key = str(a.get(1));
        long n;
        try {
            n = Long.parseLong(str(a.get(2)));
        } catch (NumberFormatException e) {
            return Reply.error(NOT_INT);
        }
        long now = System.currentTimeMillis();
        long expireAt;
        try {
            long span = Math.multiplyExact(n, unitMs);
            expireAt = absolute ? span : Math.addExact(now, span);
        } catch (ArithmeticException e) {
            return Reply.error("ERR invalid expire time in '" + (absolute ? "expireat" : "expire") + "' command");
        }
        if (expireAt <= now) {                               // 과거/현재 → 즉시 삭제(Redis 규약)
            return new Reply.Integer(db.delete(key) ? 1 : 0);
        }
        return new Reply.Integer(db.setExpireAt(key, expireAt) ? 1 : 0);
    }

    private Reply ttl(List<byte[]> a, boolean seconds) {
        if (a.size() != 2) {
            return arity(seconds ? "ttl" : "pttl");
        }
        long ms = db.ttlMillis(str(a.get(1)));
        if (ms < 0) {
            return new Reply.Integer(ms);                    // -1(영구) / -2(없음)
        }
        return new Reply.Integer(seconds ? (ms + 500) / 1000 : ms);
    }

    private Reply persist(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("persist");
        }
        return new Reply.Integer(db.persist(str(a.get(1))) ? 1 : 0);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
