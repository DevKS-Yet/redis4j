# REQ-KEY-FUNC-01 — 키 공간 관리·조회 명령

status: done
created: 2026-09-08
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-KEY-FUNC-01

목표: 전체 키 공간을 조회·관리하는 명령군과 다중 논리 DB(기본 16)를 제공한다.
      운영·디버깅·테스트에 필수이며 SCAN 커서 순회를 포함한다.
완료 조건 :
① MSET a 1 b 2; KEYS * → a b (순서 무관)
② SCAN 0 커서 순회로 전체 키 누락 없이 수집
③ SELECT 1 후 DB 공간 격리 확인
④ FLUSHDB 후 DBSIZE → 0
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 확장)
제약: 선행 REQ-STR-FUNC-01 / KEYS 는 O(N) — SCAN 병행 제공, SCAN 은 순회 중 변경에도 안전
      (약한 일관성 허용) / glob 패턴은 Redis 규약(*, ?, [ ]) 준수 / COPY·MOVE 는 선택(제외)

## 구현 계획 → 검증
1. store/Keyspace (Database[16] + 전역 락) + Database 확장(liveKeys·randomKey·rename·clear·swap)  → verify: 단위
2. command/KeyspaceCommands — KEYS(glob)·SCAN(cursor·MATCH·COUNT·TYPE)·RANDOMKEY·DBSIZE·
   RENAME/RENAMENX·FLUSHDB/FLUSHALL·SELECT·SWAPDB·UNLINK                                          → verify: ①②③④
3. ConnectionState 에 dbIndex(SELECT) 추가                                                        → verify: ③ 격리
4. dispatch — 전역 락을 Keyspace 로 이동, DB별 핸들러 세트 라우팅, 체인 말미 keyspace 시도         → verify: 회귀
5. 완료조건 ①②③④ + glob(?,[]) + SWAPDB + 회귀(ZSET/SET/HASH/LIST/EXP/STR/NET)                   → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store/Keyspace, store/Database, command/KeyspaceCommands,
  command/CommandDispatcher, server/{ConnectionState, RedisServer}}
- 검증 하니스: tasks/REQ-KEY-FUNC-01/artifacts/VerifyKey.java + verify_output.txt
- JUnit: redis4j/src/test/java/redis4j/KeyCommandTest.java
- 로그: tasks/REQ-KEY-FUNC-01/log.md
