package redis4j.store;

import java.util.LinkedList;

/**
 * List 값. 양끝 삽입·삭제 O(1) 이 주 연산이라 {@link LinkedList} 를 쓴다
 * (인덱스 접근은 O(n) — 학습용 클론 범위에서 수용). 원소는 바이트 안전({@code byte[]}).
 */
public final class ListObject implements RedisObject {

    private final LinkedList<byte[]> items = new LinkedList<>();

    public LinkedList<byte[]> items() {
        return items;
    }

    @Override
    public RedisType type() {
        return RedisType.LIST;
    }
}
