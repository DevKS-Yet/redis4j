package redis4j.pubsub;

import redis4j.protocol.Reply;

/**
 * 구독 연결로 메시지를 밀어 넣는 출구. 발행자 스레드가 구독자 소켓에 쓸 때 쓴다.
 * fire-and-forget — 전송 실패(연결 끊김)는 조용히 무시한다. 구현체는 연결별 쓰기 직렬화를 보장한다.
 */
@FunctionalInterface
public interface MessageSink {
    void send(Reply reply);
}
