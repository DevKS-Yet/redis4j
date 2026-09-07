package redis4j.store;

import java.nio.charset.StandardCharsets;

/** String 값. 바이트 안전(임의 바이트 보존). 불변 — 변경 시 새 인스턴스로 교체한다. */
public final class StringObject implements RedisObject {

    private final byte[] value;

    public StringObject(byte[] value) {
        this.value = value;
    }

    public static StringObject of(String value) {
        return new StringObject(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] value() {
        return value;
    }

    @Override
    public RedisType type() {
        return RedisType.STRING;
    }
}
