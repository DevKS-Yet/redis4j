package redis4j.persistence.aof;

import redis4j.protocol.RespDecoder;
import redis4j.store.Database;
import redis4j.store.HashObject;
import redis4j.store.Keyspace;
import redis4j.store.ListObject;
import redis4j.store.SetObject;
import redis4j.store.StringObject;
import redis4j.store.ZSetObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * AOF(추가 전용 로그) 관리자. 쓰기 명령을 RESP 명령열로 append 하고(사람이 읽을 수 있음),
 * 기동 시 재생으로 상태를 복원한다. fsync 정책 always/everysec/no. BGREWRITEAOF 는 현재 상태
 * 기준 최소 명령셋으로 압축 재작성(임시파일→원자적 rename). 다중 DB 는 {@code SELECT} 로 표기.
 *
 * <p>append 는 명령 실행 경로(전역 락 아래)에서 호출된다. everysec 강제는 데몬 스케줄러가 수행.
 */
public final class AofManager {

    public enum Fsync { ALWAYS, EVERYSEC, NO }

    private final Keyspace keyspace;
    private final Path path;
    private final boolean enabled;
    private final Fsync fsync;

    private FileOutputStream fos;
    private BufferedOutputStream out;
    private int lastAppendDb;
    private ScheduledExecutorService fsyncScheduler;

    public AofManager(Keyspace keyspace, Path path, boolean enabled, Fsync fsync) {
        this.keyspace = keyspace;
        this.path = path;
        this.enabled = enabled;
        this.fsync = fsync;
    }

    public static Fsync parseFsync(String s) {
        return switch (s == null ? "everysec" : s.toLowerCase(java.util.Locale.ROOT)) {
            case "always" -> Fsync.ALWAYS;
            case "no" -> Fsync.NO;
            default -> Fsync.EVERYSEC;
        };
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    /** AOF 파일을 RESP 명령열로 파싱한다(기동 재생용). */
    public List<List<byte[]>> readAll() throws IOException {
        List<List<byte[]>> commands = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            List<byte[]> cmd;
            while ((cmd = RespDecoder.readCommand(in)) != null) {
                commands.add(cmd);
            }
        }
        return commands;
    }

    /** append 스트림을 연다(재생 후 이어쓰기). everysec 스케줄러 시작. */
    public synchronized void open() throws IOException {
        fos = new FileOutputStream(path.toFile(), true);        // append 모드
        out = new BufferedOutputStream(fos);
        lastAppendDb = 0;
        if (fsync == Fsync.EVERYSEC) {
            fsyncScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "redis4j-aof-fsync");
                t.setDaemon(true);
                return t;
            });
            fsyncScheduler.scheduleAtFixedRate(this::forceQuietly, 1, 1, TimeUnit.SECONDS);
        }
    }

    /** 쓰기 명령을 기록한다(현재 DB가 바뀌면 SELECT 선행). 전역 락 아래에서 호출됨. */
    public synchronized void append(int db, List<byte[]> command) {
        if (out == null) {
            return;
        }
        try {
            if (db != lastAppendDb) {
                out.write(encode(cmd("SELECT", Integer.toString(db))));
                lastAppendDb = db;
            }
            out.write(encode(command));
            out.flush();                                        // OS 버퍼까지
            if (fsync == Fsync.ALWAYS) {
                fos.getChannel().force(false);                  // 디스크까지
            }
        } catch (IOException e) {
            System.err.println("AOF append 실패: " + e.getMessage());
        }
    }

    /** BGREWRITEAOF: 현재 상태를 최소 명령셋으로 압축 재작성(전역 락 아래 원자적 교체). */
    public synchronized void rewrite() throws IOException {
        byte[] compact;
        synchronized (keyspace) {
            compact = buildRewrite();
        }
        if (out != null) {
            out.flush();
            out.close();                                        // Windows: 열린 핸들이 있으면 rename 불가 → 먼저 닫음
            out = null;
        }
        writeAtomic(compact);
        fos = new FileOutputStream(path.toFile(), true);        // 새 파일로 append 재개
        out = new BufferedOutputStream(fos);
        lastAppendDb = -1;                                      // 다음 append 가 SELECT 재선행
    }

    public synchronized void close() {
        if (fsyncScheduler != null) {
            fsyncScheduler.shutdownNow();
        }
        if (out != null) {
            try {
                out.flush();
                fos.getChannel().force(true);
                out.close();
            } catch (IOException ignored) {
                // 무시
            }
        }
    }

    private void forceQuietly() {
        synchronized (this) {
            if (fos == null) {
                return;
            }
            try {
                out.flush();
                fos.getChannel().force(false);
            } catch (IOException ignored) {
                // 무시
            }
        }
    }

    /** 현재 키 공간을 재구성하는 최소 명령셋 바이트(절대 만료 PEXPIREAT 포함). 호출자가 keyspace 락 보유. */
    private byte[] buildRewrite() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < keyspace.count(); i++) {
                Database db = keyspace.db(i);
                List<Database.LiveEntry> entries = db.liveEntries();
                if (entries.isEmpty()) {
                    continue;
                }
                baos.write(encode(cmd("SELECT", Integer.toString(i))));
                for (Database.LiveEntry e : entries) {
                    baos.write(encode(rebuildCommand(e.key(), e.value())));
                    if (e.expireAtMillis() != 0) {
                        baos.write(encode(cmd("PEXPIREAT", e.key(), Long.toString(e.expireAtMillis()))));
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("in-memory rewrite failed", e);
        }
        return baos.toByteArray();
    }

    private static List<byte[]> rebuildCommand(String key, redis4j.store.RedisObject value) {
        List<byte[]> c = new ArrayList<>();
        switch (value.type()) {
            case STRING -> {
                c.add(bytes("SET"));
                c.add(bytes(key));
                c.add(((StringObject) value).value());
            }
            case LIST -> {
                c.add(bytes("RPUSH"));
                c.add(bytes(key));
                for (byte[] item : ((ListObject) value).items()) {
                    c.add(item);
                }
            }
            case HASH -> {
                c.add(bytes("HSET"));
                c.add(bytes(key));
                for (Map.Entry<String, byte[]> f : ((HashObject) value).fields().entrySet()) {
                    c.add(bytes(f.getKey()));
                    c.add(f.getValue());
                }
            }
            case SET -> {
                c.add(bytes("SADD"));
                c.add(bytes(key));
                for (String m : ((SetObject) value).members()) {
                    c.add(bytes(m));
                }
            }
            case ZSET -> {
                c.add(bytes("ZADD"));
                c.add(bytes(key));
                for (Map.Entry<String, Double> m : ((ZSetObject) value).scores().entrySet()) {
                    c.add(bytes(fmtScore(m.getValue())));
                    c.add(bytes(m.getKey()));
                }
            }
        }
        return c;
    }

    private void writeAtomic(byte[] data) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Path tmp = Files.createTempFile(dir, "aof-", ".tmp");
        try {
            Files.write(tmp, data);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static List<byte[]> cmd(String... parts) {
        List<byte[]> c = new ArrayList<>(parts.length);
        for (String p : parts) {
            c.add(bytes(p));
        }
        return c;
    }

    private static byte[] encode(List<byte[]> command) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(("*" + command.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : command) {
            b.writeBytes(("$" + arg.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            b.writeBytes(arg);
            b.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        return b.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String fmtScore(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }
}
