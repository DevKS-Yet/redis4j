# redis4j

Java 21로 밑바닥부터 구현하는 Redis 클론. 요구사항 정본은 저장소 루트
`요구사항정의서_엑셀양식_v3_1_3.xlsx`(`요구사항정의서` 시트).

## 현재 단계

- **REQ-NET-FUNC-01** — RESP2 프로토콜 TCP 서버 기반 (완료·검증)
  - RESP2 인코더/디코더, Virtual Threads 기반 다중 접속, 명령 디스패처
  - 지원 명령: `PING` · `ECHO` · `COMMAND`(최소) · `QUIT`
- **REQ-STR-FUNC-01** — String 자료형 + 중앙 키 저장소 (완료·검증)
  - 중앙 키 공간(딕셔너리, lazy 만료), 바이트 안전 값, 명령 단위 원자 실행
  - 지원 명령: `SET`(NX/XX/EX/PX/EXAT/PXAT/KEEPTTL/GET) · `GET` · `GETSET` · `GETDEL` ·
    `APPEND` · `STRLEN` · `SETNX` · `MSET` · `MSETNX` · `MGET` ·
    `INCR` · `DECR` · `INCRBY` · `DECRBY` · `INCRBYFLOAT` · `DEL` · `EXISTS` · `TYPE`
- **REQ-EXP-FUNC-01** — 키 만료(TTL) 서브시스템 (완료·검증)
  - 수동(lazy) + 능동(데몬 스케줄러, 사이클당 상한) 만료
  - 지원 명령: `EXPIRE` · `PEXPIRE` · `EXPIREAT` · `PEXPIREAT` · `TTL` · `PTTL` · `PERSIST`
- **REQ-LIST-FUNC-01** — List 자료형 (완료·검증)
  - `LinkedList<byte[]>` 기반 양방향, 빈 리스트 자동 삭제
  - 지원 명령: `LPUSH` · `RPUSH` · `LPUSHX` · `RPUSHX` · `LPOP`/`RPOP`(count) ·
    `LRANGE` · `LLEN` · `LINDEX` · `LSET` · `LREM` · `LTRIM` · `LINSERT`
- **REQ-HASH-FUNC-01** — Hash 자료형 (완료·검증)
  - `LinkedHashMap<String,byte[]>` 기반(삽입순서 보존), 빈 해시 자동 삭제
  - 지원 명령: `HSET` · `HMSET` · `HSETNX` · `HGET` · `HMGET` · `HGETALL` · `HDEL` ·
    `HEXISTS` · `HLEN` · `HKEYS` · `HVALS` · `HSTRLEN` · `HINCRBY` · `HINCRBYFLOAT`
- **REQ-SET-FUNC-01** — Set 자료형 (완료·검증)
  - `LinkedHashSet<String>` 기반(원소 순서 비보장), 빈 집합 자동 삭제
  - 지원 명령: `SADD` · `SREM` · `SMEMBERS` · `SISMEMBER` · `SMISMEMBER` · `SCARD` ·
    `SPOP` · `SRANDMEMBER` · `SINTER`/`SUNION`/`SDIFF`(+`STORE`) · `SMOVE`

## 기술 스택

- Java 21 (LTS) · Virtual Threads (thread-per-connection, 블로킹 소켓)
- Gradle (Kotlin DSL) · 외부 네트워크 프레임워크 미사용(표준 라이브러리)

## 실행

```bash
# Gradle (IDE 임포트 시 래퍼 자동 생성, 또는 `gradle wrapper`)
gradle run                 # 기본 포트 6379
gradle run --args="7000"   # 포트 지정

# 또는 JDK 직접
javac -d out $(find src/main/java -name '*.java')
java -cp out redis4j.Main 6379
```

접속 확인:

```bash
redis-cli -p 6379 ping     # → PONG
redis-cli -p 6379 echo hi  # → "hi"
```

## 테스트

```bash
gradle test    # JUnit 통합 테스트 (src/test/java)
```
