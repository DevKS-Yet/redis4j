package redis4j.command;

import redis4j.protocol.Reply;
import redis4j.pubsub.PubSub;
import redis4j.pubsub.Subscriber;
import redis4j.server.ConnectionState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 발행/구독 명령. 구독 확인·메시지는 연결 sink 로 직접 밀어 넣으므로 SUBSCRIBE 계열은 {@code null}
 * (자체 전송, 디스패처가 추가 응답을 쓰지 않음)을 반환한다. PUBLISH/PUBSUB 는 일반 응답을 반환.
 * 키공간 락과 무관 — PubSub 레지스트리 자체 락으로 동시성 안전.
 */
public final class PubSubCommands {

    private final PubSub pubSub;

    public PubSubCommands(PubSub pubSub) {
        this.pubSub = pubSub;
    }

    public Reply execute(String name, List<byte[]> a, ConnectionState state) {
        return switch (name) {
            case "SUBSCRIBE" -> subscribe(a, state, false);
            case "PSUBSCRIBE" -> subscribe(a, state, true);
            case "UNSUBSCRIBE" -> unsubscribe(a, state, false);
            case "PUNSUBSCRIBE" -> unsubscribe(a, state, true);
            case "PUBLISH" -> publish(a);
            case "PUBSUB" -> pubsubMeta(a);
            default -> null;
        };
    }

    private Reply subscribe(List<byte[]> a, ConnectionState state, boolean pattern) {
        if (a.size() < 2) {
            return Reply.error("ERR wrong number of arguments for '"
                    + (pattern ? "psubscribe" : "subscribe") + "' command");
        }
        Subscriber sub = state.subscriberOrCreate();
        String kind = pattern ? "psubscribe" : "subscribe";
        for (int i = 1; i < a.size(); i++) {
            String target = str(a.get(i));
            if (pattern) {
                pubSub.psubscribe(sub, target);
            } else {
                pubSub.subscribe(sub, target);
            }
            sub.send(new Reply.Array(List.of(
                    Reply.bulk(kind), Reply.bulk(target), new Reply.Integer(sub.subscriptionCount()))));
        }
        return null;                                            // 확인 메시지 자체 전송
    }

    private Reply unsubscribe(List<byte[]> a, ConnectionState state, boolean pattern) {
        Subscriber sub = state.subscriberOrCreate();
        String kind = pattern ? "punsubscribe" : "unsubscribe";
        List<String> targets = new ArrayList<>();
        if (a.size() >= 2) {
            for (int i = 1; i < a.size(); i++) {
                targets.add(str(a.get(i)));
            }
        } else {
            targets.addAll(pattern ? sub.patterns() : sub.channels());  // 인자 없음 → 전체 해제
        }
        if (targets.isEmpty()) {                                // 구독 없음 → nil 채널 확인 1건
            sub.send(new Reply.Array(List.of(
                    Reply.bulk(kind), new Reply.Nil(), new Reply.Integer(sub.subscriptionCount()))));
            return null;
        }
        for (String target : targets) {
            if (pattern) {
                pubSub.punsubscribe(sub, target);
            } else {
                pubSub.unsubscribe(sub, target);
            }
            sub.send(new Reply.Array(List.of(
                    Reply.bulk(kind), Reply.bulk(target), new Reply.Integer(sub.subscriptionCount()))));
        }
        return null;
    }

    private Reply publish(List<byte[]> a) {
        if (a.size() != 3) {
            return Reply.error("ERR wrong number of arguments for 'publish' command");
        }
        return new Reply.Integer(pubSub.publish(str(a.get(1)), a.get(2)));
    }

    private Reply pubsubMeta(List<byte[]> a) {
        if (a.size() < 2) {
            return Reply.error("ERR wrong number of arguments for 'pubsub' command");
        }
        String sub = str(a.get(1)).toUpperCase(Locale.ROOT);
        switch (sub) {
            case "CHANNELS": {
                String pattern = a.size() >= 3 ? str(a.get(2)) : null;
                List<Reply> out = new ArrayList<>();
                for (String ch : pubSub.channelList(pattern)) {
                    out.add(Reply.bulk(ch));
                }
                return new Reply.Array(out);
            }
            case "NUMSUB": {
                List<Reply> out = new ArrayList<>();
                for (int i = 2; i < a.size(); i++) {
                    String ch = str(a.get(i));
                    out.add(Reply.bulk(ch));
                    out.add(new Reply.Integer(pubSub.numSub(ch)));
                }
                return new Reply.Array(out);
            }
            case "NUMPAT":
                return new Reply.Integer(pubSub.numPat());
            default:
                return Reply.error("ERR Unknown PUBSUB subcommand or wrong number of arguments for '"
                        + str(a.get(1)) + "'");
        }
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
