# REQ-LIST-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK.

**[임의 결정 5] 자료구조 = LinkedList<byte[]>** — 양끝 push/pop O(1)이 List의 주 연산. 인덱스 접근(LINDEX/LSET/LRANGE)은 O(n)이나 학습용 클론에 수용 가능(Redis quicklist 모방은 범위 밖).

**[임의 결정 6] 옵션 명령 제외** — 요구 목록의 "(선택) RPOPLPUSH/LMOVE"는 이번 범위에서 제외(요구가 '선택'으로 표기). 후속 필요 시 추가.

**[임의 결정 7] 빈 리스트 자동 삭제** — LPOP/RPOP/LREM/LTRIM 등으로 리스트가 비면 키를 즉시 삭제(완료조건 ③ 및 Redis 규약).

**[임의 결정 8] STR 완료조건 ③ 종단 재검증** — STR 단계에서 타입 생성 명령이 없어 주입으로만 확인했던 "비-String GET→WRONGTYPE"를, 이제 LPUSH로 실제 List를 만든 뒤 GET→WRONGTYPE로 사용자 명령만으로 종단 재확인.

## 액션 (표준 태그)
[2026-09-07 23:15] [APPROVAL] 사용자 일괄 승인 하에 REQ-LIST-FUNC-01 진행(inline).
[2026-09-07 23:15] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-07 23:25] [DECISION] 구현 — store/ListObject(LinkedList<byte[]>) · command/ListCommands(LPUSH/RPUSH/LPUSHX/RPUSHX·LPOP/RPOP(count)·LRANGE·LLEN·LINDEX·LSET·LREM·LTRIM·LINSERT) · 디스패처 String→Expire→List 라우팅. JUnit ListCommandTest 추가.
[2026-09-07 23:30] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyList PASS=36 FAIL=0 (artifacts/verify_output.txt) + 회귀 EXP 24/0 · STR 30/0 · NET 7/0:
  ① RPUSH+LRANGE · ② LPOP+LLEN · ③ 전부 제거 후 EXISTS→0(자동삭제) · ④ String에 LPUSH→WRONGTYPE + GET(list)→WRONGTYPE(STR③ 종단) · LPUSH 역순 · LINDEX/LSET · LREM(양방향) · LTRIM · LINSERT(BEFORE/AFTER/미발견 -1) · LPUSHX/RPUSHX · LPOP count
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(store·command 확장)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-LIST-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 STR · 블로킹(BLPOP/BRPOP) 제외 · 자료형 불일치 WRONGTYPE
  - [x] Do NOT 위반 없음. EXP·STR·NET 회귀 없음
[2026-09-07 23:30] [DECISION] 요구사항 시트 REQ-LIST-FUNC-01 진행현황 기획중→테스트중.
[2026-09-07 23:31] [COMPLETE] REQ-LIST-FUNC-01 구현·검증 완료. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-HASH-FUNC-01.
