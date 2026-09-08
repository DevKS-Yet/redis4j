package redis4j.store;

/**
 * 다중 논리 DB 컨테이너(기본 16개). 이 인스턴스 자체가 전역 실행 락으로 쓰여
 * 명령 단위 원자성을 보장한다(Redis 단일 스레드 실행 모델 — SWAPDB·FLUSHALL 처럼
 * 여러 DB를 건드리는 명령까지 단일 모니터로 직렬화).
 */
public final class Keyspace {

    public static final int DEFAULT_DB_COUNT = 16;

    private final Database[] dbs;

    public Keyspace() {
        this(DEFAULT_DB_COUNT);
    }

    public Keyspace(int count) {
        dbs = new Database[count];
        for (int i = 0; i < count; i++) {
            dbs[i] = new Database();
        }
    }

    /** 논리 DB 개수. */
    public int count() {
        return dbs.length;
    }

    /** index 번째 논리 DB. */
    public Database db(int index) {
        return dbs[index];
    }

    /** 전체 DB 비우기(FLUSHALL). */
    public void flushAll() {
        for (Database d : dbs) {
            d.clear();
        }
    }

    /** 두 DB 내용 교환(SWAPDB). i==j 면 무동작. */
    public void swap(int i, int j) {
        if (i != j) {
            dbs[i].swapContentsWith(dbs[j]);
        }
    }

    /** 모든 DB에 대해 능동 만료 한 사이클(DB당 최대 maxPerDb 검사). 제거 수 합계. */
    public int activeExpireCycle(int maxPerDb) {
        int removed = 0;
        for (Database d : dbs) {
            removed += d.activeExpireCycle(maxPerDb);
        }
        return removed;
    }
}
