package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.store.Database;
import redis4j.store.ListObject;
import redis4j.store.RedisObject;
import redis4j.store.RedisType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;

/**
 * List 자료형 명령. CommandDispatcher 가 {@code synchronized(db)} 로 감싸 호출한다.
 * 미처리 명령이면 null. 블로킹 계열(BLPOP/BRPOP)은 범위 밖.
 */
public final class ListCommands {

    private static final String WRONGTYPE =
            "WRONGTYPE Operation against a key holding the wrong kind of value";
    private static final String NOT_INT = "ERR value is not an integer or out of range";

    private final Database db;

    public ListCommands(Database db) {
        this.db = db;
    }

    public Reply execute(String name, List<byte[]> a) {
        return switch (name) {
            case "LPUSH" -> push(a, true, false);
            case "RPUSH" -> push(a, false, false);
            case "LPUSHX" -> push(a, true, true);
            case "RPUSHX" -> push(a, false, true);
            case "LPOP" -> pop(a, true);
            case "RPOP" -> pop(a, false);
            case "LLEN" -> llen(a);
            case "LRANGE" -> lrange(a);
            case "LINDEX" -> lindex(a);
            case "LSET" -> lset(a);
            case "LREM" -> lrem(a);
            case "LTRIM" -> ltrim(a);
            case "LINSERT" -> linsert(a);
            default -> null;
        };
    }

    private Reply push(List<byte[]> a, boolean head, boolean requireExists) {
        String cmd = requireExists ? (head ? "lpushx" : "rpushx") : (head ? "lpush" : "rpush");
        if (a.size() < 3) {
            return arity(cmd);
        }
        String key = str(a.get(1));
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            if (requireExists) {
                return new Reply.Integer(0);
            }
            list = new ListObject();
            db.put(key, list, 0L);
        }
        for (int i = 2; i < a.size(); i++) {
            if (head) {
                list.items().addFirst(a.get(i));
            } else {
                list.items().addLast(a.get(i));
            }
        }
        return new Reply.Integer(list.items().size());
    }

    private Reply pop(List<byte[]> a, boolean head) {
        if (a.size() < 2 || a.size() > 3) {
            return arity(head ? "lpop" : "rpop");
        }
        String key = str(a.get(1));
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        boolean hasCount = a.size() == 3;
        if (list == null) {
            return new Reply.Nil();
        }
        if (!hasCount) {
            byte[] v = head ? list.items().pollFirst() : list.items().pollLast();
            deleteIfEmpty(key, list);
            return Reply.bulk(v);
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
        for (long i = 0; i < count && !list.items().isEmpty(); i++) {
            out.add(Reply.bulk(head ? list.items().pollFirst() : list.items().pollLast()));
        }
        deleteIfEmpty(key, list);
        return new Reply.Array(out);
    }

    private Reply llen(List<byte[]> a) {
        if (a.size() != 2) {
            return arity("llen");
        }
        RedisObject o = db.get(str(a.get(1)));
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        return new Reply.Integer(o == null ? 0 : ((ListObject) o).items().size());
    }

    private Reply lrange(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("lrange");
        }
        Integer s = parseInt(a.get(2));
        Integer e = parseInt(a.get(3));
        if (s == null || e == null) {
            return Reply.error(NOT_INT);
        }
        RedisObject o = db.get(str(a.get(1)));
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            return new Reply.Array(List.of());
        }
        int len = list.items().size();
        int start = s < 0 ? s + len : s;
        int stop = e < 0 ? e + len : e;
        if (start < 0) {
            start = 0;
        }
        if (stop >= len) {
            stop = len - 1;
        }
        if (start > stop || start >= len) {
            return new Reply.Array(List.of());
        }
        List<Reply> out = new ArrayList<>();
        int i = 0;
        for (byte[] item : list.items()) {
            if (i > stop) {
                break;
            }
            if (i >= start) {
                out.add(Reply.bulk(item));
            }
            i++;
        }
        return new Reply.Array(out);
    }

    private Reply lindex(List<byte[]> a) {
        if (a.size() != 3) {
            return arity("lindex");
        }
        Integer idx = parseInt(a.get(2));
        if (idx == null) {
            return Reply.error(NOT_INT);
        }
        RedisObject o = db.get(str(a.get(1)));
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            return new Reply.Nil();
        }
        int len = list.items().size();
        int i = idx < 0 ? idx + len : idx;
        if (i < 0 || i >= len) {
            return new Reply.Nil();
        }
        return Reply.bulk(list.items().get(i));
    }

    private Reply lset(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("lset");
        }
        Integer idx = parseInt(a.get(2));
        if (idx == null) {
            return Reply.error(NOT_INT);
        }
        RedisObject o = db.get(str(a.get(1)));
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            return Reply.error("ERR no such key");
        }
        int len = list.items().size();
        int i = idx < 0 ? idx + len : idx;
        if (i < 0 || i >= len) {
            return Reply.error("ERR index out of range");
        }
        list.items().set(i, a.get(3));
        return Reply.ok();
    }

    private Reply lrem(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("lrem");
        }
        Integer count = parseInt(a.get(2));
        if (count == null) {
            return Reply.error(NOT_INT);
        }
        String key = str(a.get(1));
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            return new Reply.Integer(0);
        }
        byte[] value = a.get(3);
        int removed = 0;
        if (count >= 0) {                                    // 앞→뒤, count==0 이면 전부
            ListIterator<byte[]> it = list.items().listIterator();
            while (it.hasNext()) {
                if (Arrays.equals(it.next(), value)) {
                    it.remove();
                    removed++;
                    if (count != 0 && removed >= count) {
                        break;
                    }
                }
            }
        } else {                                             // 뒤→앞, |count| 개
            int limit = -count;
            ListIterator<byte[]> it = list.items().listIterator(list.items().size());
            while (it.hasPrevious()) {
                if (Arrays.equals(it.previous(), value)) {
                    it.remove();
                    removed++;
                    if (removed >= limit) {
                        break;
                    }
                }
            }
        }
        deleteIfEmpty(key, list);
        return new Reply.Integer(removed);
    }

    private Reply ltrim(List<byte[]> a) {
        if (a.size() != 4) {
            return arity("ltrim");
        }
        Integer s = parseInt(a.get(2));
        Integer e = parseInt(a.get(3));
        if (s == null || e == null) {
            return Reply.error(NOT_INT);
        }
        String key = str(a.get(1));
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            return Reply.ok();
        }
        int len = list.items().size();
        int start = s < 0 ? s + len : s;
        int stop = e < 0 ? e + len : e;
        if (start < 0) {
            start = 0;
        }
        if (stop >= len) {
            stop = len - 1;
        }
        if (start > stop || start >= len) {                  // 전부 제거
            db.delete(key);
            return Reply.ok();
        }
        for (int i = 0; i < start; i++) {
            list.items().pollFirst();
        }
        int removeBack = (len - 1) - stop;
        for (int i = 0; i < removeBack; i++) {
            list.items().pollLast();
        }
        deleteIfEmpty(key, list);
        return Reply.ok();
    }

    private Reply linsert(List<byte[]> a) {
        if (a.size() != 5) {
            return arity("linsert");
        }
        String where = str(a.get(2)).toUpperCase(Locale.ROOT);
        boolean before;
        if (where.equals("BEFORE")) {
            before = true;
        } else if (where.equals("AFTER")) {
            before = false;
        } else {
            return Reply.error("ERR syntax error");
        }
        String key = str(a.get(1));
        RedisObject o = db.get(key);
        if (o != null && o.type() != RedisType.LIST) {
            return Reply.error(WRONGTYPE);
        }
        ListObject list = (ListObject) o;
        if (list == null) {
            return new Reply.Integer(0);
        }
        byte[] pivot = a.get(3);
        byte[] value = a.get(4);
        ListIterator<byte[]> it = list.items().listIterator();
        boolean found = false;
        while (it.hasNext()) {
            if (Arrays.equals(it.next(), pivot)) {
                found = true;
                break;
            }
        }
        if (!found) {
            return new Reply.Integer(-1);
        }
        if (before) {
            it.previous();                                   // 커서를 pivot 앞으로
            it.add(value);
        } else {
            it.add(value);                                   // pivot 뒤에 삽입
        }
        return new Reply.Integer(list.items().size());
    }

    private void deleteIfEmpty(String key, ListObject list) {
        if (list.items().isEmpty()) {
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
