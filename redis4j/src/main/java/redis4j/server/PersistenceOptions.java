package redis4j.server;

import java.nio.file.Path;

/**
 * 영속화 설정. RDB 스냅샷 경로와 AOF(appendonly) 설정을 담는다. {@code autoLoad} 가 켜지면
 * 기동 시 복원한다(AOF 우선 — appendOnly 이고 AOF 파일이 있으면 AOF 재생, 아니면 RDB 로드).
 */
public record PersistenceOptions(
        Path rdbPath,
        boolean appendOnly,
        Path aofPath,
        String fsyncPolicy,
        boolean autoLoad) {

    /** 기본값: RDB=dump.rdb4j, AOF off, 기동 자동 로드 안 함(테스트 격리). */
    public static PersistenceOptions defaults() {
        return new PersistenceOptions(Path.of("dump.rdb4j"), false, Path.of("appendonly.aof"), "everysec", false);
    }

    /** RDB 전용, 기동 자동 로드 켬(지정 경로). */
    public static PersistenceOptions rdb(Path rdbPath) {
        return new PersistenceOptions(rdbPath, false, Path.of("appendonly.aof"), "everysec", true);
    }
}
