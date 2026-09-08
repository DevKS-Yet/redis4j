# REQ-TX-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK(gradle·redis-cli 미설치).

**[임의 결정 5] WATCH = 감시 키 버전 + 전역 epoch, 인플레이스 변경 대응은 디스패처 쓰기 경로에서 버전 증가** — 자료형 명령 다수가 객체를 in-place 변경(LPUSH·SADD·ZADD…)해 Database.put 훅으로는 못 잡는다. 그래서 버전 증가를 `CommandDispatcher.executeData`(모든 데이터 명령의 단일 실행 경로)에서 수행 → 기존 6개 자료형 명령 클래스 무수정. WATCH 된 적 있는 키만 `Transactions`에 추적(메모리 절약), FLUSHDB/FLUSHALL/SWAPDB 같은 대량 변경은 전역 epoch 로 일괄 무효화(안전측 과잉취소 허용).

**[임의 결정 6] EXEC 취소 = RESP2 Null Array(`*-1`)** — Redis 규약에 맞춰 `Reply.NilArray` 타입을 sealed 계층·RespEncoder 에 추가(Null Bulk `$-1` 과 구분). 감시 위반 시 EXEC 는 NilArray 반환. EXEC 실행 구간은 `synchronized(ks)` 로 다른 클라이언트와 원자적 격리(제약 준수).

**[임의 결정 7] 큐잉 중 오류 판별 범위 = 미지 명령·(P)SUBSCRIBE 계열만 EXECABORT** — 실행 전 판별이 필요한 "문법 오류"를 미지 명령(`CommandCatalog.KNOWN` 미포함)과 트랜잭션 금지 명령((P)SUBSCRIBE/(P)UNSUBSCRIBE)으로 한정. arity 오류는 실행 전 전용 표가 없어 큐잉 통과 후 EXEC 시 런타임 오류(배열 원소 오류)로 표면화 — Redis 는 arity 도 EXECABORT 지만, 학습 클론에서는 명령별 arity 표 중복을 피하려 이 절충을 택함(로그로 표면화). 롤백 없음: EXEC 중 런타임 오류는 해당 원소만 오류, 나머지 진행.

**[임의 결정 8] 버전 증가는 쓰기 명령·성공 여부 무관 일괄 수행** — WATCH 정확도상 "놓친 취소(missed abort)"가 "과잉 취소(false abort)"보다 위험하므로, 쓰기 명령이면 실제 변경 여부와 무관하게 대상 키 버전을 올린다(안전측). WATCH 는 MULTI 밖에서만 허용, DISCARD/EXEC 는 WATCH 도 해제.

## 액션 (표준 태그)
[2026-09-08 02:20] [APPROVAL] 사용자 일괄 승인 하에 REQ-TX-FUNC-01 진행(inline).
[2026-09-08 02:20] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-08 02:40] [DECISION] 구현 — tx/{TxState,Transactions} · command/CommandCatalog · CommandDispatcher(MULTI/EXEC/DISCARD/WATCH/UNWATCH·+QUEUED 큐잉·executeData 일원화·버전증가·watch dirty·EXEC 원자실행) · protocol/{Reply.NilArray,RespEncoder} · server/ConnectionState.tx(). JUnit TransactionCommandTest 추가.
[2026-09-08 02:45] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyTx PASS=27 FAIL=0 (artifacts/verify_output.txt) + 회귀 PUBSUB 18/0 · KEY 34/0 · ZSET 42/0 · SET 30/0 · HASH 31/0 · LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0 (총 279/0):
  ① MULTI;SET a 1;INCR a;EXEC→[OK,2] · ② WATCH 위반→nil(*-1)·미위반→실행·UNWATCH 후 실행 · ③ DISCARD 후 큐 비움·상태 복귀 · ④ 미지 명령 큐잉→EXECABORT·폐기 · 런타임 오류 원소만 오류/나머지 진행 · 제어 오류(EXEC/DISCARD without MULTI·MULTI 중첩)
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(tx·command·protocol·server)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-TX-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 STR · EXEC 원자 격리(synchronized ks) · 롤백 없음 · EXECABORT
  - [x] Do NOT 위반 없음. NilArray 추가·dispatcher 개편에도 PUBSUB~NET 회귀 없음
[2026-09-08 02:46] [DECISION] 요구사항 시트 REQ-TX-FUNC-01 진행현황 기획중→테스트중.
[2026-09-08 02:46] [COMPLETE] REQ-TX-FUNC-01 구현·검증 완료. 트랜잭션·낙관적 잠금 도입. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-RDB-FUNC-01.
