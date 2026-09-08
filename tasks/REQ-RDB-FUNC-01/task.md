# REQ-RDB-FUNC-01 — RDB 스냅샷 영속화

status: done
created: 2026-09-08
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: claude-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: persistence-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-RDB-FUNC-01

목표: 전체 데이터셋을 파일로 스냅샷 저장하고 기동 시 복원하는 영속화. 재시작 후 데이터 보존.
완료 조건 :
① 데이터 적재 후 SAVE → 파일 생성
② 서버 재시작 → 이전 키·값·TTL 복원
③ 저장 중 크래시에도 기존 스냅샷 파일 온전
④ 빈 데이터셋 저장/로드 정상
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=claude-main, 리뷰=codex-critic, SubAgent=persistence-expert
산출물: 소스 코드 (redis4j/ persistence·command·server 확장)
제약: 선행 자료형(STR/LIST/HASH/SET/ZSET) / 저장은 임시파일 후 원자적 rename(손상 방지) /
      자체 포맷 허용(버전 헤더 포함) / 저장 중 일관 스냅샷(쓰기와 격리)

## 구현 계획 → 검증
1. persistence/rdb/RdbCodec — 자체 포맷(MAGIC+VERSION, SELECTDB/ENTRY/EOF opcode), 전 자료형·TTL     → verify: 단위
2. persistence/rdb/RdbManager — SAVE(락 아래 일관 직렬화)·BGSAVE(데몬 스레드 디스크쓰기)·LASTSAVE,
   임시파일→원자적 rename, loadIfExists                                                              → verify: ①③
3. store/Database.liveEntries(값·TTL 스냅샷)                                                          → verify: 직렬화
4. command/ServerCommands — SAVE/BGSAVE/LASTSAVE, 디스패처 executeData 경로에 연결                    → verify: ①
5. server/RedisServer — RdbManager 배선, 기동 시 자동 로드(명시 경로 생성자만), 종료 시 bg 정리 ·
   Main 은 dump.rdb4j 로 자동 로드 활성화                                                             → verify: ②
6. 완료조건 ①②③④ + 다중 DB·전 자료형·BGSAVE·LASTSAVE + 회귀(TX/PUBSUB/…/NET)                        → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{persistence/rdb/{RdbCodec,RdbManager}, command/ServerCommands,
  command/CommandDispatcher, store/Database, server/RedisServer, Main}
- 검증 하니스: tasks/REQ-RDB-FUNC-01/artifacts/VerifyRdb.java + verify_output.txt
- JUnit: redis4j/src/test/java/redis4j/RdbPersistenceTest.java
- 로그: tasks/REQ-RDB-FUNC-01/log.md
