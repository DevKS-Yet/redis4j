package redis4j.pubsub;

import redis4j.protocol.Reply;
import redis4j.util.GlobMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 채널·패턴 구독 레지스트리. 모든 맵 조작은 이 인스턴스 모니터로 직렬화한다(다중 스레드 안전).
 * 발행은 대상 스냅샷을 락 안에서 수집한 뒤 락 밖에서 전송한다(블로킹 소켓 쓰기를 락과 분리).
 */
public final class PubSub {

    private final Map<String, Set<Subscriber>> channels = new LinkedHashMap<>();
    private final Map<String, Set<Subscriber>> patterns = new LinkedHashMap<>();

    private record PatternHit(String pattern, Subscriber sub) {}

    public synchronized void subscribe(Subscriber s, String channel) {
        s.channels().add(channel);
        channels.computeIfAbsent(channel, k -> new LinkedHashSet<>()).add(s);
    }

    public synchronized void unsubscribe(Subscriber s, String channel) {
        s.channels().remove(channel);
        Set<Subscriber> set = channels.get(channel);
        if (set != null) {
            set.remove(s);
            if (set.isEmpty()) {
                channels.remove(channel);
            }
        }
    }

    public synchronized void psubscribe(Subscriber s, String pattern) {
        s.patterns().add(pattern);
        patterns.computeIfAbsent(pattern, k -> new LinkedHashSet<>()).add(s);
    }

    public synchronized void punsubscribe(Subscriber s, String pattern) {
        s.patterns().remove(pattern);
        Set<Subscriber> set = patterns.get(pattern);
        if (set != null) {
            set.remove(s);
            if (set.isEmpty()) {
                patterns.remove(pattern);
            }
        }
    }

    /** 연결 종료 시 이 구독자의 모든 채널·패턴 구독을 제거. */
    public synchronized void removeAll(Subscriber s) {
        for (String ch : new ArrayList<>(s.channels())) {
            unsubscribe(s, ch);
        }
        for (String p : new ArrayList<>(s.patterns())) {
            punsubscribe(s, p);
        }
    }

    /** 채널로 발행. 수신 구독자(채널 + 매칭 패턴) 수를 반환. 전송은 락 밖에서 fire-and-forget. */
    public int publish(String channel, byte[] message) {
        List<Subscriber> chSubs;
        List<PatternHit> patSubs;
        synchronized (this) {
            chSubs = snapshot(channels.get(channel));
            patSubs = new ArrayList<>();
            for (Map.Entry<String, Set<Subscriber>> e : patterns.entrySet()) {
                if (GlobMatcher.matches(e.getKey(), channel)) {
                    for (Subscriber s : e.getValue()) {
                        patSubs.add(new PatternHit(e.getKey(), s));
                    }
                }
            }
        }
        int count = 0;
        Reply channelMessage = new Reply.Array(List.of(
                Reply.bulk("message"), Reply.bulk(channel), Reply.bulk(message)));
        for (Subscriber s : chSubs) {
            s.send(channelMessage);
            count++;
        }
        for (PatternHit h : patSubs) {
            Reply patternMessage = new Reply.Array(List.of(
                    Reply.bulk("pmessage"), Reply.bulk(h.pattern()), Reply.bulk(channel), Reply.bulk(message)));
            h.sub().send(patternMessage);
            count++;
        }
        return count;
    }

    /** 구독자가 있는 활성 채널 목록(pattern != null 이면 필터). PUBSUB CHANNELS. */
    public synchronized List<String> channelList(String pattern) {
        List<String> out = new ArrayList<>();
        for (String ch : channels.keySet()) {
            if (pattern == null || GlobMatcher.matches(pattern, ch)) {
                out.add(ch);
            }
        }
        return out;
    }

    /** 특정 채널의 구독자 수. PUBSUB NUMSUB. */
    public synchronized int numSub(String channel) {
        Set<Subscriber> set = channels.get(channel);
        return set == null ? 0 : set.size();
    }

    /** 구독자가 있는 고유 패턴 수. PUBSUB NUMPAT. */
    public synchronized int numPat() {
        return patterns.size();
    }

    private static List<Subscriber> snapshot(Set<Subscriber> set) {
        return set == null ? List.of() : new ArrayList<>(set);
    }
}
