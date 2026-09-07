# REQ-HASH-FUNC-01 — Hash 자료형 명령 세트

status: done
created: 2026-09-07
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-HASH-FUNC-01

목표: 필드-값 맵을 저장하는 Hash 자료형과 관련 명령을 제공한다.
완료 조건 :
① HSET h f1 v1 f2 v2 → 2, HGET h f1 → v1
② HGETALL h → f1 v1 f2 v2
③ HDEL h f1; HEXISTS h f1 → 0
④ HINCRBY h cnt 5 → 5
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 확장)
제약: 선행 REQ-STR-FUNC-01 / 자료형 불일치 시 WRONGTYPE

## 구현 계획 → 검증
1. store/HashObject (LinkedHashMap<String,byte[]>, 삽입순서 보존)                    → verify: 단위
2. command/HashCommands — HSET/HSETNX/HMSET·HGET/HMGET/HGETALL·HDEL/HEXISTS/HLEN/
   HKEYS/HVALS·HINCRBY/HINCRBYFLOAT·HSTRLEN                                          → verify: ①②③④
3. 빈 해시 자동 삭제                                                                  → verify: ③
4. dispatch 라우팅에 Hash 추가 (String→Expire→List→Hash→unknown)                     → verify: 회귀
5. 완료조건 ①②③④ + 회귀(LIST/EXP/STR/NET)                                           → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store/HashObject, command/HashCommands, command/CommandDispatcher}
- 검증 하니스: tasks/REQ-HASH-FUNC-01/artifacts/VerifyHash.java + verify_output.txt
- 로그: tasks/REQ-HASH-FUNC-01/log.md
