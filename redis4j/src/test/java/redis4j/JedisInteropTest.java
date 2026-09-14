package redis4j;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.resps.Tuple;
import redis4j.server.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-CORE-NFR-02 검증기준② — 표준 클라이언트(Jedis) 상호운용 스모크.
 * ServerIntegrationTest 의 직접 소켓 하니스와 달리, 제3자 클라이언트 라이브러리가
 * 접속 핸드셰이크(CLIENT SETINFO 등 미지 명령 포함)를 거쳐 RESP2 로 왕복되는지 실증한다.
 */
class JedisInteropTest {

    private RedisServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = new RedisServer(0);
        port = server.start();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private Jedis client() {
        return new Jedis("127.0.0.1", port);
    }

    @Test
    void connects_and_pings() {                                     // Layer1: 핸드셰이크 성립
        try (Jedis j = client()) {
            assertEquals("PONG", j.ping());
            assertEquals("hi", j.echo("hi"));
        }
    }

    @Test
    void string_type_roundtrip() {
        try (Jedis j = client()) {
            assertEquals("OK", j.set("s", "v"));
            assertEquals("v", j.get("s"));
            assertEquals(1L, j.incr("n"));
            assertEquals(3L, j.incrBy("n", 2));
            j.set("a", "1");
            j.set("b", "2");
            assertEquals(List.of("1", "2"), j.mget("a", "b"));
        }
    }

    @Test
    void collection_types_roundtrip() {
        try (Jedis j = client()) {
            assertEquals(2L, j.rpush("L", "x", "y"));
            assertEquals(List.of("x", "y"), j.lrange("L", 0, -1));
            assertEquals("x", j.lpop("L"));

            j.hset("H", "f1", "v1");
            j.hset("H", "f2", "v2");
            assertEquals(Map.of("f1", "v1", "f2", "v2"), j.hgetAll("H"));
            assertEquals(7L, j.hincrBy("H", "cnt", 7));

            assertEquals(2L, j.sadd("S", "a", "b", "a"));
            assertEquals(Set.of("a", "b"), j.smembers("S"));

            j.zadd("Z", 1.0, "one");
            j.zadd("Z", 2.0, "two");
            List<Tuple> ranked = j.zrangeWithScores("Z", 0, -1);
            assertEquals(2, ranked.size());
            assertEquals("one", ranked.get(0).getElement());
            assertEquals(1.0, ranked.get(0).getScore());
            assertEquals(List.of("two"), j.zrangeByScore("Z", 2, 2));
            assertEquals(1L, j.zrank("Z", "two"));
        }
    }

    @Test
    void expiry_and_keyspace() {
        try (Jedis j = client()) {
            j.set("k", "v");
            assertEquals(1L, j.expire("k", 100));
            long ttl = j.ttl("k");
            assertTrue(ttl > 0 && ttl <= 100, "ttl=" + ttl);
            assertEquals(1L, j.persist("k"));
            assertEquals(-1L, j.ttl("k"));
            assertTrue(j.exists("k"));
            assertEquals(1L, j.del("k"));
            assertFalse(j.exists("k"));

            j.select(1);                                            // 멀티 논리 DB 격리
            j.set("only1", "x");
            assertEquals("x", j.get("only1"));
            j.select(0);
            assertNull(j.get("only1"));
        }
    }

    @Test
    void transaction_multi_exec_and_watch_abort() {
        try (Jedis j = client()) {
            Transaction t = j.multi();
            t.set("tk", "tv");
            Response<String> got = t.get("tk");
            List<Object> res = t.exec();
            assertEquals(2, res.size());
            assertEquals("tv", got.get());
        }
        try (Jedis j1 = client(); Jedis j2 = client()) {            // WATCH 위반 → 취소
            j1.set("w", "1");
            j1.watch("w");
            j2.set("w", "2");                                       // 감시 키를 다른 연결이 변경
            Transaction t = j1.multi();
            t.get("w");
            assertNull(t.exec());                                   // nil array → null
        }
    }

    @Test
    void pubsub_delivery() throws InterruptedException {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> got = new AtomicReference<>();
        Jedis sub = client();
        Thread listener = new Thread(() -> sub.subscribe(new JedisPubSub() {
            @Override
            public void onSubscribe(String channel, int count) {
                subscribed.countDown();
            }

            @Override
            public void onMessage(String channel, String message) {
                got.set(message);
                received.countDown();
                unsubscribe();
            }
        }, "news"));
        listener.start();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS), "subscribe timeout");

        try (Jedis pub = client()) {
            assertEquals(1L, pub.publish("news", "hello"));
        }
        assertTrue(received.await(5, TimeUnit.SECONDS), "message timeout");
        assertEquals("hello", got.get());
        listener.join(2000);
        sub.close();
    }

    @Test
    void wrongtype_surfaces_as_exception() {
        try (Jedis j = client()) {
            j.set("str", "x");
            JedisDataException ex = assertThrows(JedisDataException.class, () -> j.lpush("str", "y"));
            assertTrue(ex.getMessage().contains("WRONGTYPE"), ex.getMessage());
        }
    }
}
