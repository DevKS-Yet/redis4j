package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.store.Database;
import redis4j.store.RedisObject;
import redis4j.store.RedisType;
import redis4j.store.SetObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Set 자료형 명령. CommandDispatcher 가 {@code synchronized(db)} 로 감싸 호출한다. 미처리 시 null.
 * 원소 순서는 보장하지 않는다.
 */
public final class SetCommands {

    private static final String WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final String NOT_INT = "ERR value is not an integer or out of range";

    private final Database db;

    public SetCommands(Database db) {
        this.db = db;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "SADD" -> sadd(a);
            case "SREM" -> srem(a);
            case "SMEMBERS" -> smembers(a);
            case "SISMEMBER" -> sismember(a);
            case "SMISMEMBER" -> smismember(a);
            case "SCARD" -> scard(a);
            case "SPOP" -> spop(a);
            case "SRANDMEMBER" -> srandmember(a);
            case "SINTER" -> setOp(a, 1, Op.INTER);
            case "SUNION" -> setOp(a, 1, Op.UNION);
            case "SDIFF" -> setOp(a, 1, Op.DIFF);
            case "SINTERSTORE" -> store(a, Op.INTER);
            case "SUNIONSTORE" -> store(a, Op.UNION);
            case "SDIFFSTORE" -> store(a, Op.DIFF);
            case "SMOVE" -> smove(a);
            default -> null;
        };
    }

    private enum Op { INTER, UNION, DIFF }

    private Reply sadd(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("sadd");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        if (set == null) {
            set = new SetObject();
            db.put(key, set, 0L);
        }
        int added = 0;
        for (int i = 2; i < a.size(); i++) {
            if (set.members().add(str(a.get(i)))) {
                added++;
            }
        }
        return new Reply.Integer(added);
    }

    private Reply srem(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("srem");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        if (set == null) {
            return new Reply.Integer(0);
        }
        int removed = 0;
        for (int i = 2; i < a.size(); i++) {
            if (set.members().remove(str(a.get(i)))) {
                removed++;
            }
        }
        deleteIfEmpty(key, set);
        return new Reply.Integer(removed);
    }

    private Reply smembers(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("smembers");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        return membersArray(set == null ? Set.of() : set.members());
    }

    private Reply sismember(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("sismember");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        return new Reply.Integer(set != null && set.members().contains(str(a.get(2))) ? 1 : 0);
    }

    private Reply smismember(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("smismember");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        List<Reply> out = new ArrayList<>(a.size() - 2);
        for (int i = 2; i < a.size(); i++) {
            out.add(new Reply.Integer(set != null && set.members().contains(str(a.get(i))) ? 1 : 0));
        }
        return new Reply.Array(out);
    }

    private Reply scard(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("scard");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        return new Reply.Integer(set == null ? 0 : set.members().size());
    }

    private Reply spop(List<byte[]> a) {
        if (a.size() < 2 || a.size() > 3) {
            return arity("spop");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        boolean hasCount = a.size() == 3;
        if (set == null) {
            return hasCount ? new Reply.Array(List.of()) : new Reply.Nil();
        }
        if (!hasCount) {
            Iterator<String> it = set.members().iterator();
            String m = it.next();
            it.remove();
            deleteIfEmpty(key, set);
            return Reply.bulk(m);
        }
        long count;
        try {
            count = Long.parseLong(str(a.get(2)));
        } catch (NumberFormatException e) {
            return Reply.error(NOT_INT);
        }
        if (count < 0) {
            return Reply.error("ERR value is out of range, must be positive");
        }
        List<Reply> out = new ArrayList<>();
        Iterator<String> it = set.members().iterator();
        for (long i = 0; i < count && it.hasNext(); i++) {
            out.add(Reply.bulk(it.next()));
            it.remove();
        }
        deleteIfEmpty(key, set);
        return new Reply.Array(out);
    }

    private Reply srandmember(List<byte[]> a) {
        if (a.size() < 2 || a.size() > 3) {
            return arity("srandmember");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject set = setAt(key);
        if (a.size() == 2) {
            if (set == null || set.members().isEmpty()) {
                return new Reply.Nil();
            }
            return Reply.bulk(set.members().iterator().next());
        }
        long count;
        try {
            count = Long.parseLong(str(a.get(2)));
        } catch (NumberFormatException e) {
            return Reply.error(NOT_INT);
        }
        if (set == null || set.members().isEmpty()) {
            return new Reply.Array(List.of());
        }
        List<String> members = new ArrayList<>(set.members());
        List<Reply> out = new ArrayList<>();
        if (count >= 0) {                                    // 서로 다른 원소 최대 count
            for (int i = 0; i < count && i < members.size(); i++) {
                out.add(Reply.bulk(members.get(i)));
            }
        } else {                                             // |count| 개, 중복 허용
            int need = (int) -count;
            for (int i = 0; i < need; i++) {
                out.add(Reply.bulk(members.get(i % members.size())));
            }
        }
        return new Reply.Array(out);
    }

    private Reply setOp(List<byte[]> a, int start, Op op) {
        String cmd = op == Op.INTER ? "sinter" : op == Op.UNION ? "sunion" : "sdiff";
        if (a.size() < start + 1) {
            return arity(cmd);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int i = start; i < a.size(); i++) {
            RedisObject o = db.get(str(a.get(i)));
            if (o != null && o.type() != RedisType.SET) {
                return Reply.error(WRONGTYPE);
            }
            Set<String> members = o == null ? Set.of() : ((SetObject) o).members();
            if (i == start) {
                result.addAll(members);
            } else {
                switch (op) {
                    case INTER -> result.retainAll(members);
                    case UNION -> result.addAll(members);
                    case DIFF -> result.removeAll(members);
                }
            }
        }
        return membersArray(result);
    }

    private Reply store(List<byte[]> a, Op op) {
        String cmd = op == Op.INTER ? "sinterstore" : op == Op.UNION ? "sunionstore" : "sdiffstore";
        if (a.size() < 3) {
            return arity(cmd);
        }
        String dest = str(a.get(1));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (int i = 2; i < a.size(); i++) {
            RedisObject o = db.get(str(a.get(i)));
            if (o != null && o.type() != RedisType.SET) {
                return Reply.error(WRONGTYPE);
            }
            Set<String> members = o == null ? Set.of() : ((SetObject) o).members();
            if (i == 2) {
                result.addAll(members);
            } else {
                switch (op) {
                    case INTER -> result.retainAll(members);
                    case UNION -> result.addAll(members);
                    case DIFF -> result.removeAll(members);
                }
            }
        }
        if (result.isEmpty()) {
            db.delete(dest);                                 // 결과 공집합 → dest 삭제(Redis 규약)
        } else {
            SetObject set = new SetObject();
            set.members().addAll(result);
            db.put(dest, set, 0L);
        }
        return new Reply.Integer(result.size());
    }

    private Reply smove(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("smove");
        }
        String src = str(a.get(1));
        String dst = str(a.get(2));
        String member = str(a.get(3));
        if (wrongType(src) || wrongType(dst)) {
            return Reply.error(WRONGTYPE);
        }
        SetObject s = setAt(src);
        if (s == null || !s.members().contains(member)) {
            return new Reply.Integer(0);
        }
        s.members().remove(member);
        SetObject d = setAt(dst);
        if (d == null) {
            d = new SetObject();
            db.put(dst, d, 0L);
        }
        d.members().add(member);
        deleteIfEmpty(src, s);
        return new Reply.Integer(1);
    }

    // ---- helpers ----

    private SetObject setAt(String key) {
        RedisObject o = db.get(key);
        return (o instanceof SetObject s) ? s : null;
    }

    private boolean wrongType(String key) {
        RedisObject o = db.get(key);
        return o != null && o.type() != RedisType.SET;
    }

    private void deleteIfEmpty(String key, SetObject set) {
        if (set.members().isEmpty()) {
            db.delete(key);
        }
    }

    private static Reply membersArray(Collection<String> members) {
        List<Reply> out = new ArrayList<>(members.size());
        for (String m : members) {
            out.add(Reply.bulk(m));
        }
        return new Reply.Array(out);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
