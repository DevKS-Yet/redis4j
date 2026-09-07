# REQ-SET-FUNC-01 — Set 자료형 명령 세트

status: done
created: 2026-09-07
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-SET-FUNC-01

목표: 중복 없는 원소 집합을 저장하는 Set 자료형과 집합 연산을 제공한다.
완료 조건 :
① SADD s a b a → 2, SCARD s → 2
② SISMEMBER s a → 1
③ SINTER s1 s2 교집합 정확
④ SREM 후 빈 집합이면 EXISTS s → 0
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 확장)
제약: 선행 REQ-STR-FUNC-01 / 원소 순서 비보장(구현 정의) / 자료형 불일치 시 WRONGTYPE

## 구현 계획 → 검증
1. store/SetObject (LinkedHashSet<String>)                                          → verify: 단위
2. command/SetCommands — SADD/SREM·SMEMBERS·SISMEMBER/SMISMEMBER·SCARD·SPOP·
   SRANDMEMBER·SINTER/SUNION/SDIFF(+STORE)·SMOVE                                     → verify: ①②③④
3. 빈 집합 자동 삭제, STORE 결과 공집합이면 dest 삭제                                 → verify: ④
4. dispatch 라우팅에 Set 추가 (String→Expire→List→Hash→Set→unknown)                  → verify: 회귀
5. 완료조건 ①②③④ + 회귀(HASH/LIST/EXP/STR/NET)                                      → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store/SetObject, command/SetCommands, command/CommandDispatcher}
- 검증 하니스: tasks/REQ-SET-FUNC-01/artifacts/VerifySet.java + verify_output.txt
- 로그: tasks/REQ-SET-FUNC-01/log.md
