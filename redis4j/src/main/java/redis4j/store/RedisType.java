package redis4j.store;

/** 키가 담는 값의 자료형. TYPE 응답·WRONGTYPE 판정에 쓴다. STRING 외는 이후 단계에서 사용. */
public enum RedisType {
    STRING("string"),
    LIST("list"),
    HASH("hash"),
    SET("set"),
    ZSET("zset");

    private final String typeName;

    RedisType(String typeName) {
        this.typeName = typeName;
    }

    /** Redis TYPE 명령이 반환하는 소문자 이름. */
    public String typeName() {
        return typeName;
    }
}
