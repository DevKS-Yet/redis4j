package redis4j.persistence.rdb;

import redis4j.store.Database;
import redis4j.store.HashObject;
import redis4j.store.Keyspace;
import redis4j.store.ListObject;
import redis4j.store.RedisObject;
import redis4j.store.RedisType;
import redis4j.store.SetObject;
import redis4j.store.StringObject;
import redis4j.store.ZSetObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * redis4j 자체 스냅샷 포맷 직렬화/역직렬화. Redis RDB 와 호환되지 않는 독자 포맷(버전 헤더 포함).
 *
 * <p>레이아웃: {@code MAGIC(5) VERSION(1)} 다음 opcode 스트림 —
 * {@code SELECTDB(0xFE)+int} 로 논리 DB 전환, {@code ENTRY(0x00)} 마다
 * {@code expireAtMillis(long) type(byte) key(str) value(type별)}, 끝에 {@code EOF(0xFF)}.
 * 문자열/바이트열은 {@code int 길이 + 원문} 으로 기록(바이너리 안전).
 */
public final class RdbCodec {

    private static final byte[] MAGIC = "RDB4J".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;

    private static final int OP_ENTRY = 0x00;
    private static final int OP_SELECTDB = 0xFE;
    private static final int OP_EOF = 0xFF;

    private static final int T_STRING = 1;
    private static final int T_LIST = 2;
    private static final int T_HASH = 3;
    private static final int T_SET = 4;
    private static final int T_ZSET = 5;

    private RdbCodec() {}

    /** 전체 키 공간을 스냅샷 바이트로 직렬화한다(호출자가 일관성 위해 락 아래에서 호출). */
    public static byte[] serialize(Keyspace ks) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            for (int i = 0; i < ks.count(); i++) {
                List<Database.LiveEntry> entries = ks.db(i).liveEntries();
                if (entries.isEmpty()) {
                    continue;
                }
                out.writeByte(OP_SELECTDB);
                out.writeInt(i);
                for (Database.LiveEntry e : entries) {
                    out.writeByte(OP_ENTRY);
                    out.writeLong(e.expireAtMillis());
                    out.writeByte(typeCode(e.value().type()));
                    writeStr(out, e.key());
                    writeValue(out, e.value());
                }
            }
            out.writeByte(OP_EOF);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory serialization failed", e);   // BAOS 는 IOException 없음
        }
        return baos.toByteArray();
    }

    /** 스냅샷 바이트를 읽어 키 공간에 적재한다. 이미 만료된 키는 건너뛴다. */
    public static void load(byte[] data, Keyspace ks) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("bad snapshot magic");
            }
            int version = in.readByte() & 0xFF;
            if (version > VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
            long now = System.currentTimeMillis();
            int curDb = 0;
            while (true) {
                int op = in.read();
                if (op < 0 || op == OP_EOF) {
                    break;
                }
                if (op == OP_SELECTDB) {
                    curDb = in.readInt();
                    continue;
                }
                if (op != OP_ENTRY) {
                    throw new IOException("bad opcode " + op);
                }
                long expireAt = in.readLong();
                int type = in.readByte();
                String key = readStr(in);
                RedisObject value = readValue(in, type);
                if (expireAt != 0 && now >= expireAt) {
                    continue;                                   // 로드 시 이미 만료 → 스킵
                }
                ks.db(curDb).put(key, value, expireAt);
            }
        }
    }

    private static void writeValue(DataOutputStream out, RedisObject value) throws IOException {
        switch (value.type()) {
            case STRING -> writeBytes(out, ((StringObject) value).value());
            case LIST -> {
                ListObject l = (ListObject) value;
                out.writeInt(l.items().size());
                for (byte[] item : l.items()) {
                    writeBytes(out, item);
                }
            }
            case HASH -> {
                HashObject h = (HashObject) value;
                out.writeInt(h.fields().size());
                for (Map.Entry<String, byte[]> f : h.fields().entrySet()) {
                    writeStr(out, f.getKey());
                    writeBytes(out, f.getValue());
                }
            }
            case SET -> {
                SetObject s = (SetObject) value;
                out.writeInt(s.members().size());
                for (String m : s.members()) {
                    writeStr(out, m);
                }
            }
            case ZSET -> {
                ZSetObject z = (ZSetObject) value;
                out.writeInt(z.scores().size());
                for (Map.Entry<String, Double> m : z.scores().entrySet()) {
                    writeStr(out, m.getKey());
                    out.writeDouble(m.getValue());
                }
            }
        }
    }

    private static RedisObject readValue(DataInputStream in, int type) throws IOException {
        switch (type) {
            case T_STRING:
                return new StringObject(readBytes(in));
            case T_LIST: {
                ListObject l = new ListObject();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    l.items().add(readBytes(in));
                }
                return l;
            }
            case T_HASH: {
                HashObject h = new HashObject();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    String field = readStr(in);
                    h.fields().put(field, readBytes(in));
                }
                return h;
            }
            case T_SET: {
                SetObject s = new SetObject();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    s.members().add(readStr(in));
                }
                return s;
            }
            case T_ZSET: {
                ZSetObject z = new ZSetObject();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    String member = readStr(in);
                    z.put(member, in.readDouble());
                }
                return z;
            }
            default:
                throw new IOException("bad type code " + type);
        }
    }

    private static int typeCode(RedisType type) {
        return switch (type) {
            case STRING -> T_STRING;
            case LIST -> T_LIST;
            case HASH -> T_HASH;
            case SET -> T_SET;
            case ZSET -> T_ZSET;
        };
    }

    private static void writeBytes(DataOutputStream out, byte[] b) throws IOException {
        out.writeInt(b.length);
        out.write(b);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        return in.readNBytes(len);
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        writeBytes(out, s.getBytes(StandardCharsets.UTF_8));
    }

    private static String readStr(DataInputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }
}
