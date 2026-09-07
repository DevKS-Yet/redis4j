# REQ-LIST-FUNC-01 — List 자료형 명령 세트

status: done
created: 2026-09-07
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-LIST-FUNC-01

목표: 양방향 삽입·삭제가 가능한 List 자료형과 관련 명령을 제공한다.
완료 조건 :
① RPUSH l a b c; LRANGE l 0 -1 → a b c
② LPOP l → a, LLEN l → 2
③ 모든 원소 제거 시 EXISTS l → 0
④ String 키에 LPUSH → WRONGTYPE
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 확장)
제약: 선행 REQ-STR-FUNC-01 / 블로킹 계열(BLPOP/BRPOP)은 후속 범위(제외) / 자료형 불일치 시 WRONGTYPE

## 구현 계획 → 검증
1. store/ListObject (LinkedList<byte[]>, 양끝 O(1))                                  → verify: 단위
2. command/ListCommands — LPUSH/RPUSH/LPUSHX/RPUSHX·LPOP/RPOP(count)·LRANGE·LLEN·
   LINDEX·LSET·LREM·LTRIM·LINSERT                                                    → verify: ①②③
3. 빈 리스트 자동 삭제                                                                → verify: ③
4. dispatch 라우팅에 List 추가 (String→Expire→List→unknown)                          → verify: 회귀
5. WRONGTYPE 양방향 (List 명령 on String / GET on List)                               → verify: ④ + STR③ 종단
6. 완료조건 ①②③④ + 회귀(EXP/STR/NET)                                                → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store/ListObject, command/ListCommands, command/CommandDispatcher}
- 검증 하니스: tasks/REQ-LIST-FUNC-01/artifacts/VerifyList.java + verify_output.txt
- 로그: tasks/REQ-LIST-FUNC-01/log.md
