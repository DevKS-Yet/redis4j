# REQ-CORE-NFR-01 — 동시성·일관성·성능 요건

status: done
created: 2026-09-09
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: server-core-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-CORE-NFR-01

목표: 다중 연결 동시 처리 환경에서 키 공간의 일관성과 명령 원자성을 보장하고, 합리적 성능을
      확보한다. 전 단계 공통 적용.
완료 조건:
① 동시 다수 클라이언트가 같은 키를 갱신해도 최종 상태 일관
② INCR 동시 호출 N회 → 정확히 N 증가(원자성)
③ 부하 테스트에서 데드락·경합 손상 미발생
④ 기준 벤치마크 리포트 산출
성격: 코드 구현(모델 확정·증명·벤치) ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=server-core-expert
산출물: 소스 코드(server 자원 상한) · 테스트 · 벤치마크 하니스 · docs/benchmark
제약: Java 21 Virtual Threads 전제, 외부 동시성 프레임워크 미사용 / 데드락 없음, 락 구간 최소화 /
      성능 수치는 초기 목표(벤치마크로 재조정 가능)

## 현황 진단 (기존 코드 = 모델 이미 확정)
- 동시성 모델: **전역 단일 모니터 `synchronized(ks)`**(모든 데이터 명령·WATCH·EXEC·능동만료를 직렬화 →
  명령 단위 원자성 = single-executor 의미) + **VT-per-connection**(accept→가상스레드). → 요구 #1·#2·#3 충족.
- 단일 락이라 락 순서 없음 → 데드락 구조적 불가. Database=HashMap(비안전)이나 전역 락 밖 접근 경로 없음.
- ClientOutput.write=synchronized → 응답·발행 프레임 무결.
- **갭**: 요구 #5 "연결/메모리 자원 상한". 연결 정리는 이미 있음(try-with-resources·onDisconnect·graceful).
  → 연결 상한만 최소 보강. 메모리는 in-memory·무제한 설계(Redis 기본 maxmemory=0)로 문서화.

## 구현 계획 → 검증
1. server/RedisServer — accept 루프에 연결 수 상한 가드(AtomicInteger·초과 시 -ERR 후 close, 종료 시 감소).
   기본 10000, 테스트 훅(activeClients/setMaxClients)                                     → verify: 상한·정리
2. src/test/.../ConcurrencyNfrTest — ①동일키 동시 갱신 일관 ②INCR 동시 N=정확히 N ③혼합 부하 무손상 ·상한  → verify: ①②③
3. artifacts/VerifyConcurrency.java — 실소켓 독립 하니스(gradle 미설치 대응), ①②③+상한             → verify: ①②③
4. artifacts/Benchmark.java + docs/benchmark.md — 비파이프라인 SET/GET N-클라 QPS·p50/p99 리포트     → verify: ④
5. 회귀: 전 단계 Verify 하니스 재실행(RDB~NET) 손상 없음                                          → verify: all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/server/RedisServer.java (연결 상한)
- 문서: redis4j/docs/benchmark.md (동시성 모델 + 벤치 리포트)
- JUnit: redis4j/src/test/java/redis4j/ConcurrencyNfrTest.java
- 검증 하니스: tasks/REQ-CORE-NFR-01/artifacts/{VerifyConcurrency.java, Benchmark.java, verify_output.txt, benchmark_output.txt}
- 로그: tasks/REQ-CORE-NFR-01/log.md
