package redis4j.store;

import java.util.LinkedHashMap;

/**
 * Hash 값. 필드명은 UTF-8 문자열(상위 키와 동일 규약), 값은 바이트 안전({@code byte[]}).
 * 삽입 순서를 보존({@link LinkedHashMap})해 HGETALL/HKEYS/HVALS 가 결정적이다.
 */
public final class HashObject implements RedisObject {

    private final LinkedHashMap<String, byte[]> fields = new LinkedHashMap<>();

    public LinkedHashMap<String, byte[]> fields() {
        return fields;
    }

    @Override
    public RedisType type() {
        return RedisType.HASH;
    }
}
