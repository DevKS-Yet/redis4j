package redis4j.store;

import java.util.LinkedHashSet;

/**
 * Set 값. 멤버는 UTF-8 문자열(상위 키·해시 필드와 동일 규약). {@link LinkedHashSet} 로 중복을
 * 제거하며, 원소 순서는 보장하지 않는다(요구사항 제약).
 */
public final class SetObject implements RedisObject {

    private final LinkedHashSet<String> members = new LinkedHashSet<>();

    public LinkedHashSet<String> members() {
        return members;
    }

    @Override
    public RedisType type() {
        return RedisType.SET;
    }
}
