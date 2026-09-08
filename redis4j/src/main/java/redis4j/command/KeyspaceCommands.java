package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.server.ConnectionState;
import redis4j.store.Database;
import redis4j.store.Keyspace;
import redis4j.store.RedisType;

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
            if (glob(pattern, k)) {
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
            if (match != null && !glob(match, k)) {
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

    /**
     * Redis glob 매칭: {@code *}(임의 시퀀스) · {@code ?}(임의 1자) · {@code [...]}(문자 클래스,
     * {@code ^} 부정 · {@code a-z} 범위) · {@code \\} 이스케이프. 대소문자 구분.
     */
    static boolean glob(String pat, String s) {
        int p = 0;
        int si = 0;
        int starP = -1;
        int starS = -1;
        int pn = pat.length();
        int sn = s.length();
        while (si < sn) {
            char pc = p < pn ? pat.charAt(p) : '\0';
            if (p < pn && pc == '?') {
                p++;
                si++;
            } else if (p < pn && pc == '\\' && p + 1 < pn) {
                if (pat.charAt(p + 1) == s.charAt(si)) {
                    p += 2;
                    si++;
                } else if (starP >= 0) {
                    p = starP + 1;
                    si = ++starS;
                } else {
                    return false;
                }
            } else if (p < pn && pc == '[') {
                int[] r = matchClass(pat, p, s.charAt(si));
                if (r[0] == -1) {                                // 닫는 ] 없음 → '[' 리터럴
                    if (s.charAt(si) == '[') {
                        p++;
                        si++;
                    } else if (starP >= 0) {
                        p = starP + 1;
                        si = ++starS;
                    } else {
                        return false;
                    }
                } else if (r[0] == 1) {
                    p = r[1];
                    si++;
                } else if (starP >= 0) {
                    p = starP + 1;
                    si = ++starS;
                } else {
                    return false;
                }
            } else if (p < pn && pc == '*') {
                starP = p;
                starS = si;
                p++;
            } else if (p < pn && pc == s.charAt(si)) {
                p++;
                si++;
            } else if (starP >= 0) {
                p = starP + 1;
                si = ++starS;
            } else {
                return false;
            }
        }
        while (p < pn && pat.charAt(p) == '*') {
            p++;
        }
        return p == pn;
    }

    /**
     * '[' 위치에서 문자 클래스를 파싱해 c 매칭 여부와 ']' 다음 인덱스를 반환.
     * 반환 {@code [matched(0/1), nextIndex]}; 닫는 ']' 없으면 {@code [-1,-1]}(리터럴 처리).
     */
    private static int[] matchClass(String pat, int start, char c) {
        int i = start + 1;
        boolean negate = false;
        if (i < pat.length() && pat.charAt(i) == '^') {
            negate = true;
            i++;
        }
        boolean matched = false;
        while (i < pat.length() && pat.charAt(i) != ']') {
            char cur = pat.charAt(i);
            if (cur == '\\' && i + 1 < pat.length()) {
                if (pat.charAt(i + 1) == c) {
                    matched = true;
                }
                i += 2;
            } else if (i + 2 < pat.length() && pat.charAt(i + 1) == '-' && pat.charAt(i + 2) != ']') {
                char lo = cur;
                char hi = pat.charAt(i + 2);
                if (lo > hi) {
                    char t = lo;
                    lo = hi;
                    hi = t;
                }
                if (c >= lo && c <= hi) {
                    matched = true;
                }
                i += 3;
            } else {
                if (cur == c) {
                    matched = true;
                }
                i++;
            }
        }
        if (i >= pat.length()) {
            return new int[]{-1, -1};                            // 닫는 ] 없음
        }
        if (negate) {
            matched = !matched;
        }
        return new int[]{matched ? 1 : 0, i + 1};
    }
}
