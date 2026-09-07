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

    /** 존재하는(비만료) 키의 만료 시각(절대 ms, 0=만료 없음)을 설정. 키 없으면 false. 값은 유지. */
    public boolean setExpireAt(String key, long expireAtMillis) {
        if (get(key) == null) {
            return false;
        }
        Entry e = map.get(key);
        map.put(key, new Entry(e.value(), expireAtMillis));
        return true;
    }

    /** 남은 수명(ms). 없는 키 -2, 만료 없음 -1. */
    public long ttlMillis(String key) {
        Entry e = map.get(key);
        if (e == null) {
            return -2;
        }
        if (isExpired(e)) {
            map.remove(key);
            return -2;
        }
        if (e.expireAtMillis() == 0) {
            return -1;
        }
        return e.expireAtMillis() - System.currentTimeMillis();
    }

    /** 만료 제거. 만료가 설정돼 있던 키였으면 true. */
    public boolean persist(String key) {
        if (get(key) == null) {
            return false;
        }
        Entry e = map.get(key);
        if (e.expireAtMillis() == 0) {
            return false;
        }
        map.put(key, new Entry(e.value(), 0L));
        return true;
    }

    /**
     * 능동 만료 한 사이클. 만료 후보(만료 설정된 키)를 최대 {@code maxExamine} 개만 검사하고
     * 만료된 것을 제거한다(사이클당 작업량 제한). 제거 수 반환. 호출자가 synchronized(db) 로 감싼다.
     */
    public int activeExpireCycle(int maxExamine) {
        long now = System.currentTimeMillis();
        int examined = 0;
        int removed = 0;
        java.util.Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
        while (it.hasNext() && examined < maxExamine) {
            Entry v = it.next().getValue();
            if (v.expireAtMillis() != 0) {
                examined++;
                if (now >= v.expireAtMillis()) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    /** 만료 정리 없이 현재 맵 크기(검증·디버깅용). */
    public int rawSize() {
        return map.size();
    }

    private boolean isExpired(Entry e) {
        return e.expireAtMillis() != 0 && System.currentTimeMillis() >= e.expireAtMillis();
    }
}
