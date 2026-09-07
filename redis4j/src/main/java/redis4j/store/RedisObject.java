package redis4j.store;

/**
 * 키 공간에 저장되는 값. 함수형 인터페이스라 타입 태그만 가진 스텁을 람다로 만들 수 있다
 * (아직 구현되지 않은 자료형을 테스트에서 주입할 때 유용).
 */
@FunctionalInterface
public interface RedisObject {
    RedisType type();
}
