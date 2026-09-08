package redis4j.tx;

import java.util.ArrayList;
import java.util.List;

/**
 * 연결별 트랜잭션 상태. MULTI 큐잉 여부·큐·큐잉 오류(EXECABORT) 플래그와 WATCH 스냅샷을 담는다.
 * 순수 상태 홀더 — 접근은 디스패처가 전역 락 아래에서 직렬화한다.
 */
public final class TxState {

    /** WATCH 시점의 (db, key, version) 스냅샷. */
    public record Watch(int db, String key, long version) {}

    private boolean inMulti;
    private boolean queueError;
    private final List<List<byte[]>> queue = new ArrayList<>();

    private final List<Watch> watched = new ArrayList<>();
    private boolean watching;
    private long watchedEpoch;

    public boolean inMulti() {
        return inMulti;
    }

    public void beginMulti() {
        inMulti = true;
        queue.clear();
        queueError = false;
    }

    /** MULTI 종료(EXEC/DISCARD) — 큐 상태 초기화. WATCH 는 별도 {@link #unwatch()}. */
    public void endMulti() {
        inMulti = false;
        queue.clear();
        queueError = false;
    }

    public boolean queueError() {
        return queueError;
    }

    public void markQueueError() {
        queueError = true;
    }

    public void enqueue(List<byte[]> command) {
        queue.add(command);
    }

    public List<List<byte[]>> queued() {
        return queue;
    }

    public void watch(int db, String key, long version, long epoch) {
        if (!watching) {
            watching = true;
            watchedEpoch = epoch;
        }
        watched.add(new Watch(db, key, version));
    }

    public boolean watching() {
        return watching;
    }

    public List<Watch> watched() {
        return watched;
    }

    public long watchedEpoch() {
        return watchedEpoch;
    }

    public void unwatch() {
        watched.clear();
        watching = false;
        watchedEpoch = 0;
    }
}
