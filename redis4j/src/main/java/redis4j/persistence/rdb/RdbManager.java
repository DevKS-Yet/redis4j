package redis4j.persistence.rdb;

import redis4j.store.Keyspace;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RDB 스냅샷 저장/복원 관리자. 저장은 전역 락(Keyspace 모니터) 아래에서 <b>일관 스냅샷</b>을
 * 바이트로 만든 뒤(짧게 점유), 디스크 쓰기는 임시파일 → 원자적 rename 으로 수행해 저장 중
 * 크래시에도 기존 스냅샷을 보존한다. BGSAVE 는 디스크 쓰기를 데몬 스레드로 넘긴다.
 */
public final class RdbManager {

    private final Keyspace keyspace;
    private final Path path;
    private final ExecutorService bgExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redis4j-bgsave");
                t.setDaemon(true);
                return t;
            });

    private volatile long lastSaveSeconds = System.currentTimeMillis() / 1000;

    public RdbManager(Keyspace keyspace, Path path) {
        this.keyspace = keyspace;
        this.path = path;
    }

    /** 동기 저장(SAVE): 일관 스냅샷을 만들어 원자적으로 파일에 쓴다. */
    public void save() throws IOException {
        byte[] snapshot;
        synchronized (keyspace) {
            snapshot = RdbCodec.serialize(keyspace);
        }
        writeAtomic(snapshot);
        lastSaveSeconds = System.currentTimeMillis() / 1000;
    }

    /** 백그라운드 저장(BGSAVE): 스냅샷은 락 아래에서 즉시 만들고, 디스크 쓰기만 데몬 스레드로. */
    public void bgsave() {
        byte[] snapshot;
        synchronized (keyspace) {
            snapshot = RdbCodec.serialize(keyspace);
        }
        bgExecutor.submit(() -> {
            try {
                writeAtomic(snapshot);
                lastSaveSeconds = System.currentTimeMillis() / 1000;
            } catch (IOException e) {
                System.err.println("BGSAVE 실패: " + e.getMessage());
            }
        });
    }

    /** 마지막 성공 저장 시각(unix epoch 초). LASTSAVE. */
    public long lastSave() {
        return lastSaveSeconds;
    }

    /** 스냅샷 파일이 있으면 기동 시 적재한다. */
    public void loadIfExists() throws IOException {
        if (Files.exists(path)) {
            RdbCodec.load(Files.readAllBytes(path), keyspace);
        }
    }

    public void shutdown() {
        bgExecutor.shutdownNow();
    }

    /** 임시파일에 먼저 쓰고 원자적 rename — 저장 중 크래시에도 기존 파일 온전. */
    private void writeAtomic(byte[] data) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "dump-", ".rdb4j.tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);   // 폴백(비원자적)
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
