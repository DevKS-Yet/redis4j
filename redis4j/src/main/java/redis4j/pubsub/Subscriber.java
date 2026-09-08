package redis4j.pubsub;

import redis4j.protocol.Reply;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 구독 상태의 단일 연결. 자신의 출구(sink)와 구독 중인 채널·패턴 집합을 갖는다.
 * 집합의 변경/조회는 {@link PubSub} 레지스트리 락 아래에서만 이뤄진다.
 */
public final class Subscriber {

    private final MessageSink sink;
    private final Set<String> channels = new LinkedHashSet<>();
    private final Set<String> patterns = new LinkedHashSet<>();

    public Subscriber(MessageSink sink) {
        this.sink = sink;
    }

    public void send(Reply reply) {
        sink.send(reply);
    }

    public Set<String> channels() {
        return channels;
    }

    public Set<String> patterns() {
        return patterns;
    }

    /** 현재 구독 수(채널+패턴). 0 이면 구독 모드에서 빠진다. */
    public int subscriptionCount() {
        return channels.size() + patterns.size();
    }
}
