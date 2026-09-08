# REQ-ZSET-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK.

**[임의 결정 5] 자료구조 = HashMap + TreeSet** — `HashMap<String,Double>`(멤버→score, O(1) ZSCORE/ZADD 조회) + `TreeSet<ScoredMember(score,member)>`(정렬). 스킵리스트 대신 TreeSet — 범위 질의는 정확, ZRANK/ZRANGE 인덱스는 O(n)이나 학습용 클론에 수용. 동점 시 member 사전순 비교(제약 준수).

**[임의 결정 6] score 표기·파싱** — 정수 score는 소수점 없이(예 "6"), ±무한대는 "inf"/"-inf"로 표기/파싱(Redis 규약 근사). 범위 경계는 "(" 배타 / "[" 포함(lex) / "-"·"+"(lex 최소/최대) 지원.

**[임의 결정 7] 미열거 옵션 제외** — 요구 목록에 없는 LIMIT(offset/count)·ZLEXCOUNT·ZPOPMIN/MAX 등은 이번 범위 제외(후속). 열거된 명령·플래그(NX/XX/GT/LT/CH/INCR)만 구현.

**[임의 결정 8] 멤버=UTF-8 String, 빈 zset 자동 삭제** — 상위 키·해시 필드·셋 멤버와 동일 규약. ZREM/ZREMRANGE로 비면 키 삭제.

## 액션 (표준 태그)
[2026-09-08 00:30] [APPROVAL] 사용자 일괄 승인 하에 REQ-ZSET-FUNC-01 진행(inline).
[2026-09-08 00:30] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-08 00:45] [DECISION] 구현 — store/ZSetObject(HashMap 멤버→score + TreeSet<(score,member)>) · command/ZSetCommands(ZADD 플래그·ZREM·ZSCORE/ZMSCORE·ZCARD·ZCOUNT·ZINCRBY·ZRANK/ZREVRANK·ZRANGE/ZREVRANGE·ZRANGEBYSCORE/ZREVRANGEBYSCORE·ZRANGEBYLEX·ZREMRANGEBYRANK/BYSCORE) · 디스패처 …→Set→ZSet 라우팅. JUnit ZSetCommandTest 추가.
[2026-09-08 00:50] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyZSet PASS=42 FAIL=0 (artifacts/verify_output.txt) + 회귀 SET 30/0 · HASH 31/0 · LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0:
  ① ZADD+ZRANGE(+WITHSCORES) · ② ZRANGEBYSCORE(배타·inf) · ③ ZRANK/ZREVRANK · ④ ZINCRBY+ZSCORE · ZADD 플래그(NX/XX/GT/LT/CH/INCR) · ZRANGEBYLEX · ZREMRANGEBYRANK/BYSCORE · 동점 lex 정렬 · 빈 zset 자동삭제 · WRONGTYPE 양방향
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(store·command 확장)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-ZSET-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 STR · 범위 질의 정확 · 동점 lex 정렬 · WRONGTYPE
  - [x] Do NOT 위반 없음. SET·HASH·LIST·EXP·STR·NET 회귀 없음
[2026-09-08 00:50] [DECISION] 요구사항 시트 REQ-ZSET-FUNC-01 진행현황 기획중→테스트중.
[2026-09-08 00:51] [COMPLETE] REQ-ZSET-FUNC-01 구현·검증 완료. 자료형 5종(STR/LIST/HASH/SET/ZSET) 완료. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-KEY-FUNC-01.
