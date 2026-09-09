# REQ-AOF-FUNC-01 — AOF 추가 전용 로그 영속화

status: done
created: 2026-09-08
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: persistence-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-AOF-FUNC-01

목표: 쓰기 명령을 추가 전용 로그로 기록해 내구성을 높이고, 기동 시 재생하여 상태를 복원한다.
      RDB 대비 데이터 유실 창을 최소화.
완료 조건 :
① appendonly on 상태에서 쓰기 후 재시작 → 상태 복원
② AOF 파일이 사람이 읽을 수 있는 RESP 명령열
③ BGREWRITEAOF 후 파일 크기 감소·재생 결과 동일
④ everysec 에서 장애 시 유실 1초 이하
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=persistence-expert
산출물: 소스 코드 (redis4j/ persistence/aof·command·server 확장)
제약: 선행 REQ-RDB-FUNC-01(영속화 기반 재사용) / 재작성은 원자적 교체 /
      everysec 기본, 성능·내구성 균형 / 기동 시 AOF 재생이 RDB보다 우선

## 구현 계획 → 검증
1. persistence/aof/AofManager — RESP append(쓰기 명령), fsync always/everysec/no, readAll(RespDecoder
   재사용), BGREWRITEAOF(최소 명령셋·절대 PEXPIREAT·임시파일→원자 rename), 다중 DB=SELECT 표기      → verify: ①②③
2. server/PersistenceOptions — RDB/AOF 설정(appendonly·경로·fsync·autoLoad)                          → verify: 설정
3. dispatch — executeData 에서 쓰기 명령 AOF append(재생 중 억제), loadFrom(AOF 재생)                → verify: ①
4. command/ServerCommands.BGREWRITEAOF · CommandCatalog.KNOWN(BGREWRITEAOF)                           → verify: ③
5. server/RedisServer — AofManager 배선, 기동 로드 순서(AOF 우선, 없으면 RDB), 재생 후 open, 종료 정리 → verify: ①·우선
6. 완료조건 ①②③(④는 fsync 타이밍 보장·in-process 크래시 주입 불가 → always 내구성으로 대체 검증) +
   AOF 우선·다중 DB·전 자료형 + 회귀(RDB/TX/…/NET)                                                    → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{persistence/aof/AofManager, server/{PersistenceOptions,RedisServer},
  command/{ServerCommands,CommandDispatcher,CommandCatalog}}
- 검증 하니스: tasks/REQ-AOF-FUNC-01/artifacts/VerifyAof.java + verify_output.txt
- JUnit: redis4j/src/test/java/redis4j/AofPersistenceTest.java
- 로그: tasks/REQ-AOF-FUNC-01/log.md
