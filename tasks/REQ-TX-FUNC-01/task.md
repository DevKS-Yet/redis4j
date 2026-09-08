# REQ-TX-FUNC-01 — 트랜잭션(MULTI/EXEC) + WATCH 낙관적 잠금

status: done
created: 2026-09-08
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-TX-FUNC-01

목표: 명령을 큐에 모아 원자적으로 실행하는 트랜잭션과 WATCH 기반 낙관적 잠금을 제공한다.
완료 조건 :
① MULTI; SET a 1; INCR a; EXEC → [OK, 2]
② WATCH k 후 다른 연결이 k 변경 시 EXEC → (nil)
③ DISCARD 후 큐 비워지고 상태 복귀
④ 큐잉 중 문법 오류 → EXEC 시 EXECABORT
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ tx·command·protocol 확장)
제약: 선행 REQ-STR-FUNC-01 / EXEC 실행 구간은 다른 클라이언트와 원자적 격리(전역 락) /
      롤백 없음(런타임 오류는 해당 명령만 오류, 나머지 진행) / MULTI 문법 오류는 EXECABORT

## 구현 계획 → 검증
1. tx/TxState(연결별 inMulti·큐·queueError·WATCH 스냅샷) · tx/Transactions(감시 키 버전 + epoch)   → verify: 단위
2. command/CommandCatalog(KNOWN=미지명령 판별, WRITES/writtenKeys=버전 증가 대상)                    → verify: ④
3. dispatch — MULTI/EXEC/DISCARD/WATCH/UNWATCH 라우팅, +QUEUED 큐잉, executeData 일원화(쓰기 시
   버전 증가), EXEC 원자 실행(synchronized ks)·watch dirty 검사→취소                                  → verify: ①②③
4. protocol/Reply.NilArray(*-1) + RespEncoder — EXEC 취소 응답                                        → verify: ②
5. ConnectionState.tx() 추가                                                                          → verify: 상태
6. 완료조건 ①②③④ + 런타임 오류 passthrough + 제어 오류 + 회귀(PUBSUB/KEY/…/NET)                     → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{tx/{TxState,Transactions}, command/{CommandCatalog,
  CommandDispatcher}, protocol/{Reply,RespEncoder}, server/ConnectionState}
- 검증 하니스: tasks/REQ-TX-FUNC-01/artifacts/VerifyTx.java + verify_output.txt
- JUnit: redis4j/src/test/java/redis4j/TransactionCommandTest.java
- 로그: tasks/REQ-TX-FUNC-01/log.md
