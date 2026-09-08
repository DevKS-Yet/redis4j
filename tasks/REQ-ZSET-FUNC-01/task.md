# REQ-ZSET-FUNC-01 — Sorted Set 자료형 명령 세트

status: done
created: 2026-09-08
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-ZSET-FUNC-01

목표: score 기준 정렬된 집합(Sorted Set)을 제공한다. 범위·순위 질의를 지원하며
      자료구조 난이도가 가장 높은 단계.
완료 조건 :
① ZADD z 1 a 2 b 3 c; ZRANGE z 0 -1 → a b c
② ZRANGEBYSCORE z 2 3 → b c
③ ZRANK z b → 1
④ ZINCRBY z 5 a 후 ZSCORE z a → 6
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 확장)
제약: 선행 REQ-STR-FUNC-01 / 정렬 자료구조는 구현 자유(스킵리스트 불필수)이나 범위 질의 정확성·
      합리적 복잡도 확보 / score 동점 시 사전순(lex) 정렬 / 자료형 불일치 시 WRONGTYPE

## 구현 계획 → 검증
1. store/ZSetObject (HashMap 멤버→score + TreeSet<(score,member)> 정렬)              → verify: 단위
2. command/ZSetCommands — ZADD(NX/XX/GT/LT/CH/INCR)·ZREM·ZSCORE/ZMSCORE·ZCARD·ZCOUNT·
   ZINCRBY·ZRANK/ZREVRANK·ZRANGE/ZREVRANGE(WITHSCORES)·ZRANGEBYSCORE/ZREVRANGEBYSCORE·
   ZRANGEBYLEX·ZREMRANGEBYRANK/BYSCORE                                                → verify: ①②③④
3. score 동점 lex 정렬, 빈 zset 자동 삭제                                             → verify: 순서
4. dispatch 라우팅에 ZSet 추가 (…→Set→ZSet→unknown)                                   → verify: 회귀
5. 완료조건 ①②③④ + 회귀(SET/HASH/LIST/EXP/STR/NET)                                   → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store/ZSetObject, command/ZSetCommands, command/CommandDispatcher}
- 검증 하니스: tasks/REQ-ZSET-FUNC-01/artifacts/VerifyZSet.java + verify_output.txt
- 로그: tasks/REQ-ZSET-FUNC-01/log.md
