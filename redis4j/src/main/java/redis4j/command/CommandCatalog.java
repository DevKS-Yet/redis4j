package redis4j.command;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 명령 분류표(대문자 명령명). MULTI 큐잉 시 미지 명령 판별(EXECABORT)과 WATCH 버전 증가 대상
 * 키 추출에 쓴다. 각 자료형 핸들러가 이미 의미론을 갖고 있으나, 트랜잭션은 실행 전 판별이
 * 필요해 최소한의 표를 여기 둔다.
 */
public final class CommandCatalog {

    private CommandCatalog() {}

    /** 지원 명령 전체(연결·트랜잭션·발행구독·데이터·키공간). 미지 명령 판별용. */
    public static final Set<String> KNOWN = Set.of(
            // connection / tx / pubsub / server
            "PING", "ECHO", "COMMAND", "QUIT", "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
            "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", "PUBLISH", "PUBSUB",
            "SAVE", "BGSAVE", "LASTSAVE", "BGREWRITEAOF",
            // string
            "SET", "GET", "GETSET", "GETDEL", "APPEND", "STRLEN", "SETNX", "MSET", "MSETNX", "MGET",
            "INCR", "DECR", "INCRBY", "DECRBY", "INCRBYFLOAT", "DEL", "EXISTS", "TYPE",
            // expire
            "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "TTL", "PTTL", "PERSIST",
            // list
            "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP", "LRANGE", "LLEN", "LINDEX",
            "LSET", "LREM", "LTRIM", "LINSERT",
            // hash
            "HSET", "HMSET", "HSETNX", "HGET", "HMGET", "HGETALL", "HDEL", "HEXISTS", "HLEN",
            "HKEYS", "HVALS", "HSTRLEN", "HINCRBY", "HINCRBYFLOAT",
            // set
            "SADD", "SREM", "SMEMBERS", "SISMEMBER", "SMISMEMBER", "SCARD", "SPOP", "SRANDMEMBER",
            "SINTER", "SUNION", "SDIFF", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE", "SMOVE",
            // zset
            "ZADD", "ZREM", "ZSCORE", "ZMSCORE", "ZCARD", "ZCOUNT", "ZINCRBY", "ZRANK", "ZREVRANK",
            "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZREVRANGEBYSCORE", "ZRANGEBYLEX",
            "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
            // keyspace
            "KEYS", "SCAN", "RANDOMKEY", "DBSIZE", "RENAME", "RENAMENX", "FLUSHDB", "FLUSHALL",
            "SELECT", "SWAPDB", "UNLINK");

    /** 쓰기 명령(WATCH 버전 증가 대상). 대량 변경(FLUSHDB/FLUSHALL/SWAPDB)은 epoch 로 별도 처리. */
    private static final Set<String> WRITES = Set.of(
            "SET", "SETNX", "GETSET", "GETDEL", "APPEND", "INCR", "DECR", "INCRBY", "DECRBY",
            "INCRBYFLOAT", "MSET", "MSETNX", "DEL", "UNLINK",
            "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST",
            "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LPOP", "RPOP", "LSET", "LREM", "LTRIM", "LINSERT",
            "HSET", "HMSET", "HSETNX", "HDEL", "HINCRBY", "HINCRBYFLOAT",
            "SADD", "SREM", "SPOP", "SMOVE", "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE",
            "ZADD", "ZREM", "ZINCRBY", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
            "RENAME", "RENAMENX");

    public static boolean isKnown(String name) {
        return KNOWN.contains(name);
    }

    public static boolean isWrite(String name) {
        return WRITES.contains(name);
    }

    /** 쓰기 명령이 건드리는 키 목록(WATCH 버전 증가용). isWrite 가 true 인 명령에만 호출. */
    public static List<String> writtenKeys(String name, List<byte[]> a) {
        List<String> keys = new ArrayList<>();
        switch (name) {
            case "MSET", "MSETNX" -> {
                for (int i = 1; i + 1 < a.size(); i += 2) {
                    keys.add(str(a.get(i)));
                }
            }
            case "DEL", "UNLINK" -> {
                for (int i = 1; i < a.size(); i++) {
                    keys.add(str(a.get(i)));
                }
            }
            case "RENAME", "RENAMENX", "SMOVE" -> {
                if (a.size() > 1) {
                    keys.add(str(a.get(1)));
                }
                if (a.size() > 2) {
                    keys.add(str(a.get(2)));
                }
            }
            default -> {
                if (a.size() > 1) {
                    keys.add(str(a.get(1)));
                }
            }
        }
        return keys;
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
