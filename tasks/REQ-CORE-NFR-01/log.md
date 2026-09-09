# REQ-CORE-NFR-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK(gradle·redis-cli 미설치).

**[임의 결정 5] 동시성 모델은 이미 확정 — 이 단계는 확정·증명·벤치가 본질** — 전역 단일 모니터 `synchronized(keyspace)`(모든 데이터 명령·WATCH·EXEC·능동만료 직렬화 = single-executor 원자성) + VT-per-connection 은 REQ-NET~각 명령 단계에서 이미 구축됨. 요구 #1·#2·#3 충족. 따라서 신규 구현이 아니라 (a)모델 문서화 (b)①②③ 자동 증명 (c)④ 벤치 리포트가 산출물. 단일 락→데드락 구조적 불가.

**[임의 결정 6] 요구 #5 자원 상한 = 연결 상한만 최소 보강, 메모리는 무제한 설계로 문서화** — 연결 정리는 이미 존재(try-with-resources·onDisconnect·graceful). 갭인 "연결 상한"만 accept 루프에 AtomicInteger 가드(기본 10000, 초과 시 `-ERR max number of clients reached` 후 close, 종료 시 감소)로 추가. 메모리는 in-memory 무제한(Redis 기본 maxmemory=0/noeviction 동일 의미) — eviction 정책은 범위 밖(후속). 근거: 4검증기준에 자원상한 항목 없음 + 단순함 원칙.

**[임의 결정 7] ④ 성능 검증은 loopback in-process 벤치 baseline** — 파이프라인 없이(요청→응답) N 동시 클라 SET/GET QPS·p50/p99 측정. 요구서 "성능 수치는 초기 목표·재벤치로 재조정" 명시에 따라 실측 baseline 을 정직하게 리포트. 외부 redis-cli/Jedis 스모크는 미설치로 이번 제외(NFR-02 소관).

## 액션 (표준 태그)
[2026-09-09 14:10] [APPROVAL] 사용자 일괄 승인 하에 REQ-CORE-NFR-01 진행(inline). 커밋·푸시 후 진행 지시.
[2026-09-09 14:10] [DECISION] 요청문 변환 + 위 임의 결정 1~7.
[2026-09-09 14:20] [DECISION] 현황 진단 — CommandDispatcher/RedisServer/Keyspace/Database 실측: 전역 단일 모니터 + VT-per-connection 이미 확정, Database=HashMap(락 밖 접근 경로 없음), ClientOutput synchronized. 갭=연결 상한.
[2026-09-09 14:35] [DECISION] 구현 — server/RedisServer(연결 상한 가드·activeClients/setMaxClients 훅) · src/test/ConcurrencyNfrTest(①②③+상한) · artifacts/VerifyConcurrency(독립 하니스) · artifacts/Benchmark + docs/benchmark.md(④).
[2026-09-09 14:45] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyConcurrency PASS=9 FAIL=0 (artifacts/verify_output.txt):
  ① 동일 키 50클라×200 SADD → SCARD=10000(무손실 일관)
  ② INCR 50클라×1000 → 정확히 50000(원자성)  ※최초 실패는 테스트 단언 오류(GET=벌크지 정수 아님)→수정 후 PASS, 서버 정상
  ③ 40클라×500 혼합(SET/GET/INCR/RPUSH) 527ms 완료(무데드락) + counter=20000·LLEN=20000 불변식
  #5 연결 상한3 초과 거부·정리 후 activeClients=0·재수용
[2026-09-09 14:50] [VERIFICATION] ④ 벤치(artifacts/benchmark_output.txt, JDK21·12vCPU·32클라×20000):
  SET QPS=117,987 p50=0.266ms p99=0.470ms / GET QPS=113,248 p50=0.274ms p99=0.511ms — 목표(수만 QPS·p99 수 ms) 충족.
[2026-09-09 14:55] [VERIFICATION] 회귀 — 전 하니스 재컴파일·실행 ALL PASS: Verify(NET)·Str·Exp·List·Hash·Set·ZSet·Key·PubSub·Tx·Rdb·Aof 전부 PASS + Concurrency 9/0. RedisServer 변경(가법적)으로 인한 회귀 없음. cwd 오염 없음(서버 포트0·임시경로).
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(server·test·artifacts·docs/benchmark)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, redis4j/docs/**, tasks/REQ-CORE-NFR-01/**)
  - [x] task.md constraints 충족: VT 전제·외부 프레임워크 미사용·데드락 없음(단일 락)·락 구간 최소
  - [x] Do NOT 위반 없음. 전 단계 회귀 없음
[2026-09-09 14:56] [DECISION] 요구사항 시트 REQ-CORE-NFR-01(K14) 진행현황 기획중→테스트중. NFR-02(K15)는 유지.
[2026-09-09 14:57] [COMPLETE] REQ-CORE-NFR-01 구현·검증 완료. 동시성 모델 확정·증명 + 자원 상한 보강 + 기준 벤치 리포트. 다음: REQ-CORE-NFR-02(테스트·CI·호환성 — 정식 리뷰 게이트 포함).
