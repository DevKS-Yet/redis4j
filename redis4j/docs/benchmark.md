# redis4j — 동시성 모델 & 기준 벤치마크 (REQ-CORE-NFR-01)

다중 연결 동시 처리 환경에서 키 공간 일관성·명령 원자성을 보장하는 동시성 모델을 확정하고,
비파이프라인 SET/GET 처리량·지연의 baseline 을 기록한다. 전 단계에 공통 적용된다.

## 1. 동시성 모델 (확정)

| 축 | 결정 | 위치 |
|----|------|------|
| 연결 동시성 | **Virtual Thread per connection** (accept 루프 → `newVirtualThreadPerTaskExecutor`) | `RedisServer.acceptLoop` / `ConnectionHandler` |
| 실행 직렬화 | **전역 단일 모니터 `synchronized(keyspace)`** — 모든 데이터 명령·WATCH·EXEC·능동 만료를 한 모니터로 직렬화 (Redis 단일 스레드 실행 모델과 동일 의미 = single-executor) | `CommandDispatcher.dispatch/executeData/exec/watch`, `RedisServer.activeExpire` |
| 자료구조 보호 | `Database` = 일반 `HashMap`(자체 비안전). 전역 락 밖 접근 경로 없음 → 락으로 보호 | `store/Database`, `store/Keyspace` |
| 출력 프레이밍 | `ClientOutput.write` = `synchronized` — 명령 응답과 발행 메시지 푸시가 섞이지 않음 | `server/ClientOutput` |

**원자성**: 개별 명령은 전역 락 구간에서 실행되므로 다른 명령과 인터리브되지 않는다(② INCR 동시 N회 = 정확히 N).
**일관성**: 동일 키 동시 갱신도 단일 모니터로 순차화되어 손실이 없다(① 동시 SADD → 최종 SCARD 정확).
**무데드락**: 락이 단 하나(keyspace 모니터)라 락 순서·순환 대기가 성립하지 않는다 → 데드락 구조적 불가(③).
**락 구간 최소화**: 락 안에서는 in-memory 맵 조작만 수행한다. I/O(소켓 읽기/쓰기), RESP 파싱/인코딩은 락 밖.
> BGSAVE/BGREWRITEAOF 등 스냅샷·재작성은 락 하에서 일관 스냅샷을 얻는다(쓰기와 격리). fork 불가 환경의 트레이드오프.

## 2. 자원 관리 (요구 #5)

- **연결 상한**: 기본 `10,000`. 초과 시 `-ERR max number of clients reached` 후 소켓 close (`RedisServer.acceptLoop`/`rejectExcess`).
  `setMaxClients(int)` 로 조정, `activeClients()` 로 현재 수 관찰.
- **연결 정리**: 소켓 try-with-resources 종료, `onDisconnect` 구독 자동 해제, `close()` 의 graceful shutdown(리슨 소켓·VT 실행자·만료 스케줄러 정리). 활성 카운트는 연결 종료 시 감소.
- **메모리**: in-memory 저장소로 **무제한이 기본**(Redis 기본 `maxmemory=0` / `noeviction` 과 동일 의미).
  maxmemory/eviction 정책은 이번 범위 밖(후속).

## 3. 기준 벤치마크

**방법**: 파이프라인 없이(요청→응답→다음) N 개 동시 클라이언트가 자기 키에 SET/GET 을 반복. loopback 왕복 기준
집계 QPS 와 지연 백분위를 측정. warmup 후 측정. 하니스: `tasks/REQ-CORE-NFR-01/artifacts/Benchmark.java`.

**측정 결과** (JDK 21.0.9, 12 vCPU, clients=32 × 20,000 ops/type = 640,000 ops):

| 연산 | QPS | p50 | p99 | p99.9 | max |
|------|-----|-----|-----|-------|-----|
| SET | 117,987 | 0.266 ms | 0.470 ms | 0.631 ms | 1.951 ms |
| GET | 113,248 | 0.274 ms | 0.511 ms | 0.763 ms | 7.445 ms |

초기 목표(파이프라인 없이 수만 QPS급, p99 수 ms)를 여유 있게 충족. 수치는 머신·부하에 따라 달라지는
baseline 이며 재벤치로 재조정 가능하다(요구서 명시).

**재현**:
```bash
javac -d out $(find src/main/java -name "*.java") ../tasks/REQ-CORE-NFR-01/artifacts/Benchmark.java
java -cp out Benchmark [clients] [opsPerClient]   # 기본 32 20000
```

## 4. 검증 기준 매핑

| 기준 | 검증 | 결과 |
|------|------|------|
| ① 동시 동일 키 갱신 최종 일관 | 50 클라 × 200 고유 SADD → SCARD=10,000 | PASS |
| ② INCR 동시 N회 = 정확히 N | 50 클라 × 1,000 INCR → 50,000 | PASS |
| ③ 부하 테스트 무데드락·무손상 | 40 클라 × 500 혼합(SET/GET/INCR/RPUSH) 완료 + 불변식 | PASS |
| ④ 기준 벤치마크 리포트 | 위 §3 | PASS |
| (요구 #5) 연결 상한·정리 | 상한 초과 거부 + 정리 후 activeClients=0·재수용 | PASS |

JUnit: `src/test/java/redis4j/ConcurrencyNfrTest.java` · 독립 하니스: `tasks/REQ-CORE-NFR-01/artifacts/VerifyConcurrency.java` (9/0).
