package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.store.Database;
import redis4j.store.RedisObject;
import redis4j.store.RedisType;
import redis4j.store.ZSetObject;
import redis4j.store.ZSetObject.ScoredMember;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sorted Set 명령. CommandDispatcher 가 {@code synchronized(db)} 로 감싸 호출한다. 미처리 시 null.
 * 정렬은 (score, member lex). 미열거 옵션(LIMIT 등)은 범위 밖.
 */
public final class ZSetCommands {

    private static final String WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final String NOT_FLOAT = "ERR value is not a valid float";

    private final Database db;

    public ZSetCommands(Database db) {
        this.db = db;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "ZADD" -> zadd(a);
            case "ZREM" -> zrem(a);
            case "ZSCORE" -> zscore(a);
            case "ZMSCORE" -> zmscore(a);
            case "ZCARD" -> zcard(a);
            case "ZCOUNT" -> zcount(a);
            case "ZINCRBY" -> zincrby(a);
            case "ZRANK" -> zrank(a, false);
            case "ZREVRANK" -> zrank(a, true);
            case "ZRANGE" -> zrange(a, false);
            case "ZREVRANGE" -> zrange(a, true);
            case "ZRANGEBYSCORE" -> zrangebyscore(a, false);
            case "ZREVRANGEBYSCORE" -> zrangebyscore(a, true);
            case "ZRANGEBYLEX" -> zrangebylex(a);
            case "ZREMRANGEBYRANK" -> zremrangebyrank(a);
            case "ZREMRANGEBYSCORE" -> zremrangebyscore(a);
            default -> null;
        };
    }

    // ---- ZADD ----

    private Reply zadd(List<byte[]> a) {
        if (a.size() < 4) {
            return arity("zadd");
        }
        int i = 2;
        boolean nx = false, xx = false, gt = false, lt = false, ch = false, incr = false;
        while (i < a.size()) {
            String t = str(a.get(i)).toUpperCase(Locale.ROOT);
            if (t.equals("NX")) nx = true;
            else if (t.equals("XX")) xx = true;
            else if (t.equals("GT")) gt = true;
            else if (t.equals("LT")) lt = true;
            else if (t.equals("CH")) ch = true;
            else if (t.equals("INCR")) incr = true;
            else break;
            i++;
        }
        int remaining = a.size() - i;
        if (remaining < 2 || remaining % 2 != 0) {
            return arity("zadd");
        }
        if ((nx && (xx || gt || lt)) || (gt && lt)) {
            return Reply.error("ERR GT, LT, and/or NX options at the same time are not compatible");
        }
        if (incr && remaining != 2) {
            return Reply.error("ERR INCR option supports a single increment-element pair");
        }
        int pairs = remaining / 2;
        double[] scs = new double[pairs];
        String[] mems = new String[pairs];
        for (int p = 0; p < pairs; p++) {
            Double sc = parseScore(str(a.get(i + 2 * p)));
            if (sc == null) {
                return Reply.error(NOT_FLOAT);
            }
            scs[p] = sc;
            mems[p] = str(a.get(i + 2 * p + 1));
        }

        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        boolean created = false;
        if (z == null) {
            z = new ZSetObject();
            created = true;
        }

        if (incr) {
            String m = mems[0];
            Double cur = z.scoreOf(m);
            boolean exists = cur != null;
            if ((nx && exists) || (xx && !exists)) {
                return new Reply.Nil();
            }
            double nv = exists ? cur + scs[0] : scs[0];
            if ((gt && exists && !(nv > cur)) || (lt && exists && !(nv < cur))) {
                return new Reply.Nil();
            }
            z.put(m, nv);
            if (created) {
                db.put(key, z, 0L);
            }
            return Reply.bulk(fmt(nv));
        }

        int added = 0, changed = 0;
        for (int p = 0; p < pairs; p++) {
            String m = mems[p];
            Double cur = z.scoreOf(m);
            boolean exists = cur != null;
            if ((nx && exists) || (xx && !exists)) {
                continue;
            }
            double nv = scs[p];
            if ((gt && exists && !(nv > cur)) || (lt && exists && !(nv < cur))) {
                continue;
            }
            int r = z.put(m, nv);
            if (r == 1) {
                added++;
            }
            if (r == 1 || r == 2) {
                changed++;
            }
        }
        if (created && !z.isEmpty()) {
            db.put(key, z, 0L);
        }
        return new Reply.Integer(ch ? changed : added);
    }

    // ---- 단순 ----

    private Reply zrem(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("zrem");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Integer(0);
        }
        int removed = 0;
        for (int i = 2; i < a.size(); i++) {
            if (z.remove(str(a.get(i)))) {
                removed++;
            }
        }
        deleteIfEmpty(key, z);
        return new Reply.Integer(removed);
    }

    private Reply zscore(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("zscore");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        Double sc = z == null ? null : z.scoreOf(str(a.get(2)));
        return sc == null ? new Reply.Nil() : Reply.bulk(fmt(sc));
    }

    private Reply zmscore(List<byte[]> a) {
        if (a.size() < 3) {
            return arity("zmscore");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        List<Reply> out = new ArrayList<>(a.size() - 2);
        for (int i = 2; i < a.size(); i++) {
            Double sc = z == null ? null : z.scoreOf(str(a.get(i)));
            out.add(sc == null ? new Reply.Nil() : Reply.bulk(fmt(sc)));
        }
        return new Reply.Array(out);
    }

    private Reply zcard(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("zcard");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        return new Reply.Integer(z == null ? 0 : z.size());
    }

    private Reply zincrby(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("zincrby");
        }
        Double inc = parseScore(str(a.get(2)));
        if (inc == null) {
            return Reply.error(NOT_FLOAT);
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        boolean created = false;
        if (z == null) {
            z = new ZSetObject();
            created = true;
        }
        String m = str(a.get(3));
        Double cur = z.scoreOf(m);
        double nv = cur == null ? inc : cur + inc;
        z.put(m, nv);
        if (created) {
            db.put(key, z, 0L);
        }
        return Reply.bulk(fmt(nv));
    }

    private Reply zrank(List<byte[]> a, boolean rev) {
        if (a.size() != 3) {
            return arity(rev ? "zrevrank" : "zrank");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Nil();
        }
        String member = str(a.get(2));
        int idx = 0;
        for (ScoredMember sm : z.sorted()) {
            if (sm.member().equals(member)) {
                return new Reply.Integer(rev ? z.size() - 1 - idx : idx);
            }
            idx++;
        }
        return new Reply.Nil();
    }

    // ---- 범위: 인덱스 ----

    private Reply zrange(List<byte[]> a, boolean rev) {
        boolean withScores = a.size() == 5 && str(a.get(4)).equalsIgnoreCase("WITHSCORES");
        if (a.size() != 4 && !withScores) {
            return arity(rev ? "zrevrange" : "zrange");
        }
        Integer start = parseInt(a.get(2));
        Integer stop = parseInt(a.get(3));
        if (start == null || stop == null) {
            return Reply.error("ERR value is not an integer or out of range");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Array(List.of());
        }
        List<ScoredMember> all = new ArrayList<>(z.sorted());
        if (rev) {
            Collections.reverse(all);
        }
        int n = all.size();
        int s = start < 0 ? start + n : start;
        int e = stop < 0 ? stop + n : stop;
        if (s < 0) s = 0;
        if (e >= n) e = n - 1;
        if (s > e || s >= n) {
            return new Reply.Array(List.of());
        }
        return membersReply(all.subList(s, e + 1), withScores);
    }

    // ---- 범위: score ----

    private Reply zrangebyscore(List<byte[]> a, boolean rev) {
        boolean withScores = a.size() == 5 && str(a.get(4)).equalsIgnoreCase("WITHSCORES");
        if (a.size() != 4 && !withScores) {
            return arity(rev ? "zrevrangebyscore" : "zrangebyscore");
        }
        // rev: 인자 순서가 (max min)
        ScoreBound lo = parseScoreBound(str(a.get(rev ? 3 : 2)));
        ScoreBound hi = parseScoreBound(str(a.get(rev ? 2 : 3)));
        if (lo == null || hi == null) {
            return Reply.error("ERR min or max is not a float");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Array(List.of());
        }
        List<ScoredMember> matched = new ArrayList<>();
        for (ScoredMember sm : z.sorted()) {
            if (scoreInRange(sm.score(), lo, hi)) {
                matched.add(sm);
            }
        }
        if (rev) {
            Collections.reverse(matched);
        }
        return membersReply(matched, withScores);
    }

    private Reply zcount(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("zcount");
        }
        ScoreBound lo = parseScoreBound(str(a.get(2)));
        ScoreBound hi = parseScoreBound(str(a.get(3)));
        if (lo == null || hi == null) {
            return Reply.error("ERR min or max is not a float");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Integer(0);
        }
        int count = 0;
        for (ScoredMember sm : z.sorted()) {
            if (scoreInRange(sm.score(), lo, hi)) {
                count++;
            }
        }
        return new Reply.Integer(count);
    }

    // ---- 범위: lex ----

    private Reply zrangebylex(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("zrangebylex");
        }
        LexBound lo = parseLexBound(str(a.get(2)));
        LexBound hi = parseLexBound(str(a.get(3)));
        if (lo == null || hi == null) {
            return Reply.error("ERR min or max not valid string range item");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Array(List.of());
        }
        List<ScoredMember> matched = new ArrayList<>();
        for (ScoredMember sm : z.sorted()) {
            if (lexGe(sm.member(), lo) && lexLe(sm.member(), hi)) {
                matched.add(sm);
            }
        }
        return membersReply(matched, false);
    }

    // ---- 범위 제거 ----

    private Reply zremrangebyrank(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("zremrangebyrank");
        }
        Integer start = parseInt(a.get(2));
        Integer stop = parseInt(a.get(3));
        if (start == null || stop == null) {
            return Reply.error("ERR value is not an integer or out of range");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Integer(0);
        }
        List<ScoredMember> all = new ArrayList<>(z.sorted());
        int n = all.size();
        int s = start < 0 ? start + n : start;
        int e = stop < 0 ? stop + n : stop;
        if (s < 0) s = 0;
        if (e >= n) e = n - 1;
        if (s > e || s >= n) {
            return new Reply.Integer(0);
        }
        int removed = 0;
        for (int idx = s; idx <= e; idx++) {
            if (z.remove(all.get(idx).member())) {
                removed++;
            }
        }
        deleteIfEmpty(key, z);
        return new Reply.Integer(removed);
    }

    private Reply zremrangebyscore(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("zremrangebyscore");
        }
        ScoreBound lo = parseScoreBound(str(a.get(2)));
        ScoreBound hi = parseScoreBound(str(a.get(3)));
        if (lo == null || hi == null) {
            return Reply.error("ERR min or max is not a float");
        }
        String key = str(a.get(1));
        if (wrongType(key)) {
            return Reply.error(WRONGTYPE);
        }
        ZSetObject z = zsetAt(key);
        if (z == null) {
            return new Reply.Integer(0);
        }
        List<String> toRemove = new ArrayList<>();
        for (ScoredMember sm : z.sorted()) {
            if (scoreInRange(sm.score(), lo, hi)) {
                toRemove.add(sm.member());
            }
        }
        for (String m : toRemove) {
            z.remove(m);
        }
        deleteIfEmpty(key, z);
        return new Reply.Integer(toRemove.size());
    }

    // ---- helpers ----

    private record ScoreBound(double value, boolean inclusive) {}

    private record LexBound(String value, boolean inclusive, int kind) {} // kind: -1=min(-), 1=max(+), 0=일반

    private static boolean scoreInRange(double sc, ScoreBound lo, ScoreBound hi) {
        if (lo.inclusive() ? sc < lo.value() : sc <= lo.value()) {
            return false;
        }
        if (hi.inclusive() ? sc > hi.value() : sc >= hi.value()) {
            return false;
        }
        return true;
    }

    private static boolean lexGe(String m, LexBound lo) {
        if (lo.kind() == -1) return true;
        if (lo.kind() == 1) return false;
        int c = m.compareTo(lo.value());
        return lo.inclusive() ? c >= 0 : c > 0;
    }

    private static boolean lexLe(String m, LexBound hi) {
        if (hi.kind() == 1) return true;
        if (hi.kind() == -1) return false;
        int c = m.compareTo(hi.value());
        return hi.inclusive() ? c <= 0 : c < 0;
    }

    private static ScoreBound parseScoreBound(String s) {
        String t = s.trim();
        boolean inc = true;
        if (t.startsWith("(")) {
            inc = false;
            t = t.substring(1);
        }
        Double v = parseScore(t);
        return v == null ? null : new ScoreBound(v, inc);
    }

    private static LexBound parseLexBound(String s) {
        if (s.equals("-")) return new LexBound(null, true, -1);
        if (s.equals("+")) return new LexBound(null, true, 1);
        if (s.startsWith("[")) return new LexBound(s.substring(1), true, 0);
        if (s.startsWith("(")) return new LexBound(s.substring(1), false, 0);
        return null;
    }

    private static Double parseScore(String s) {
        String t = s.trim();
        if (t.equalsIgnoreCase("inf") || t.equalsIgnoreCase("+inf")) return Double.POSITIVE_INFINITY;
        if (t.equalsIgnoreCase("-inf")) return Double.NEGATIVE_INFINITY;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double d) {
        if (d == Double.POSITIVE_INFINITY) return "inf";
        if (d == Double.NEGATIVE_INFINITY) return "-inf";
        if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static Reply membersReply(List<ScoredMember> items, boolean withScores) {
        List<Reply> out = new ArrayList<>();
        for (ScoredMember sm : items) {
            out.add(Reply.bulk(sm.member()));
            if (withScores) {
                out.add(Reply.bulk(fmt(sm.score())));
            }
        }
        return new Reply.Array(out);
    }

    private ZSetObject zsetAt(String key) {
        RedisObject o = db.get(key);
        return (o instanceof ZSetObject z) ? z : null;
    }

    private boolean wrongType(String key) {
        RedisObject o = db.get(key);
        return o != null && o.type() != RedisType.ZSET;
    }

    private void deleteIfEmpty(String key, ZSetObject z) {
        if (z.isEmpty()) {
            db.delete(key);
        }
    }

    private static Integer parseInt(byte[] b) {
        try {
            return Integer.parseInt(str(b));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static Reply arity(String cmd) {
        return Reply.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}
