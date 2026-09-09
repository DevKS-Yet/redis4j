# REQ-AOF-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK(gradle·redis-cli 미설치).

**[임의 결정 5] AOF = 실행된 쓰기 명령을 RESP 로 verbatim append, 재사용 RespDecoder 로 재생** — 디스패처의 단일 실행 경로 executeData 에서 쓰기 명령(isWrite ∪ FLUSHDB/FLUSHALL/SWAPDB)을 그대로 RESP 인코딩해 append(사람이 읽는 명령열=②). 다중 DB 는 현재 DB 가 바뀔 때 `SELECT` 선행. 재생은 기존 `RespDecoder.readCommand` 로 파싱 → executeData 로 실행(재생 중 재-기록 억제 flag). **절충**: 상대 만료(EXPIRE/SET EX 등)는 verbatim 기록이라 재시작 시 만료 시계가 리셋될 수 있음 — 단, BGREWRITEAOF 는 절대 시각 `PEXPIREAT` 로 내보내 정확. (완전 정확 원하면 append 시 절대 변환 필요 — 후속.)

**[임의 결정 6] fsync always/everysec/no + everysec 기본** — append 는 매번 OS 버퍼로 flush, ALWAYS 는 즉시 `channel.force`, EVERYSEC 는 데몬 스케줄러가 1초마다 force, NO 는 OS 위임. ④(everysec 유실 ≤1s)는 fsync 타이밍 보장이며 in-process 에서 실제 크래시 주입이 불가 → 검증 하니스는 ALWAYS 내구성(쓰기→재시작 복원)으로 대체 확인하고, everysec 는 기본값으로 채택.

**[임의 결정 7] BGREWRITEAOF 는 전역 락 아래 원자적 재작성(버퍼링 불필요)** — 스레드-per-connection·fork 불가 환경에서, 재작성을 ks 락 아래에서 수행하면 재작성 중 신규 쓰기가 없어 "버퍼링 후 병합"이 불필요(제약의 취지를 락으로 충족). 순서: 스냅샷 명령셋 생성 → **열린 AOF 핸들 닫기(Windows 는 열린 파일 rename 불가)** → 임시파일 기록 → 원자적 rename → 새 파일로 append 재개. 최소 명령셋: 자료형별 재구성(SET/RPUSH/HSET/SADD/ZADD) + 만료는 절대 PEXPIREAT.

**[임의 결정 8] 기동 로드 = AOF 우선, 설정은 생성자 옵션** — appendonly on 이고 AOF 파일 있으면 AOF 재생(RDB 무시), 아니면 RDB 로드(요구서 "RDB보다 우선"). 설정(appendonly·경로·fsync)은 런타임 CONFIG 대신 `PersistenceOptions` 생성자 옵션으로(테스트 격리 위해 `RedisServer(int)`는 AOF off·자동로드 off 유지). Main 은 기존대로 RDB 자동로드(appendonly off) — 실 서버 AOF 활성화는 옵션으로 전환.

## 액션 (표준 태그)
[2026-09-08 22:05] [APPROVAL] 사용자 일괄 승인 하에 REQ-AOF-FUNC-01 진행(inline).
[2026-09-08 22:05] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-08 22:35] [DECISION] 구현 — persistence/aof/AofManager(append·readAll·open·rewrite·fsync 정책) · server/PersistenceOptions · CommandDispatcher(executeData AOF append·loadFrom 재생·replaying 억제) · command/ServerCommands.BGREWRITEAOF · CommandCatalog.KNOWN(BGREWRITEAOF) · RedisServer(AofManager 배선·AOF 우선 로드·open·close). JUnit AofPersistenceTest 추가.
[2026-09-08 22:45] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyAof PASS=16 FAIL=0 (artifacts/verify_output.txt) + 회귀 RDB 22/0 · TX 27/0 · PUBSUB 18/0 · KEY 34/0 · ZSET 42/0 · SET 30/0 · HASH 31/0 · LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0 (총 317/0). 작업 디렉터리 오염 없음:
  ① appendonly 재시작→전 자료형·다중 DB 복원 · ② AOF 가 RESP 명령열(SET/RPUSH/SELECT 가독) · ③ BGREWRITEAOF 2156→80B 감소·재생 동일(c=100,k=v2) · fsync always 내구성 · AOF 우선(RDB 공존 시 AOF 재생)
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(persistence/aof·server·command)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-AOF-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 RDB 재사용 · 원자적 재작성 · everysec 기본 · AOF 우선 로드
  - [x] Do NOT 위반 없음. 디스패처/서버 변경에도 RDB~NET 회귀 없음, cwd 오염 없음
[2026-09-08 22:46] [DECISION] 요구사항 시트 REQ-AOF-FUNC-01 진행현황 기획중→테스트중.
[2026-09-08 22:46] [COMPLETE] REQ-AOF-FUNC-01 구현·검증 완료. 영속화 2종(RDB·AOF) 완료. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-CORE-NFR-01.
