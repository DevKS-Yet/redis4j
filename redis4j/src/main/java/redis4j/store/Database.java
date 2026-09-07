package redis4j.store;

import java.util.HashMap;
import java.util.Map;

/**
 * 단일 논리 DB의 중앙 키 공간. lazy(수동) 만료를 포함한다 — 만료된 키는 접근 시 제거된다.
 *
 * <p>스레드 안전하지 않다. 호출자(CommandDispatcher)가 {@code synchronized (db)} 로 명령 단위
 * 원자성을 보장한다(Redis 단일 스레드 실행 모델과 동일 의미). 능동 만료·TTL 명령은 EXPIRE 단계.
 */
public final class Database {

    /** expireAtMillis == 0 이면 만료 없음. */
    private record Entry(RedisObject value, long expireAtMillis) {}

    private final Map<String, Entry> map = new HashMap<>();

    /** 값 조회. 없거나 만료면 null(만료면 제거까지 수행). */
    public RedisObject get(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return null;
        }
        if (isExpired(e)) {
            map.remove(key);
            return null;
        }
        return e.value();
    }

    /** 값과 만료(절대 ms, 0=없음)를 지정해 저장. */
    public void put(String key, RedisObject value, long expireAtMillis) {
        map.put(key, new Entry(value, expireAtMillis));
    }

    /** 기존 만료를 유지하며 값만 교체(APPEND·INCR·SET KEEPTTL). 기존 만료가 없으면 만료 없음. */
    public void putKeepTtl(String key, RedisObject value) {
        Entry e = map.get(key);
        long ttl = (e != null && !isExpired(e)) ? e.expireAtMillis() : 0L;
        map.put(key, new Entry(value, ttl));
    }

    public boolean exists(String key) {
        return get(key) != null;
    }

    /** 삭제. 존재(비만료)했으면 true. */
    public boolean delete(String key) {
        if (get(key) == null) {
            return false;
        }
        map.remove(key);
        return true;
    }

    /** 없으면 null. */
    public RedisType typeOf(String key) {
        RedisObject o = get(key);
        return o == null ? null : o.type();
    }

    private boolean isExpired(Entry e) {
        return e.expireAtMillis() != 0 && System.currentTimeMillis() >= e.expireAtMillis();
    }
}
