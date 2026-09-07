# REQ-HASH-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK.

**[임의 결정 5] 자료구조 = LinkedHashMap<String, byte[]>** — 필드명은 UTF-8 String(상위 키와 동일 규약), 값은 byte[](바이트 안전). 삽입 순서 보존이라 HGETALL/HKEYS/HVALS가 결정적이며 Redis listpack(소형 해시) 순서와 일치 → 검증 용이.

**[임의 결정 6] HSCAN(선택) 제외** — 커서 순회는 SCAN 계열이므로 REQ-KEY-FUNC-01 단계로 이관.

**[임의 결정 7] 빈 해시 자동 삭제** — HDEL로 마지막 필드가 제거되면 키를 즉시 삭제(Redis 규약).

**[임의 결정 8] HSET 반환 = 새로 추가된 필드 수** — Redis 4+ 규약(갱신 제외, 신규 필드만 카운트). HMSET는 +OK(레거시 별칭). HSETNX는 :1/:0.

## 액션 (표준 태그)
[2026-09-07 23:40] [APPROVAL] 사용자 일괄 승인 하에 REQ-HASH-FUNC-01 진행(inline).
[2026-09-07 23:40] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-07 23:50] [DECISION] 구현 — store/HashObject(LinkedHashMap<String,byte[]>) · command/HashCommands(HSET/HMSET/HSETNX·HGET/HMGET/HGETALL·HDEL/HEXISTS/HLEN/HKEYS/HVALS·HINCRBY/HINCRBYFLOAT·HSTRLEN) · 디스패처 String→Expire→List→Hash 라우팅. JUnit HashCommandTest 추가.
[2026-09-07 23:55] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyHash PASS=31 FAIL=0 (artifacts/verify_output.txt) + 회귀 LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0:
  ① HSET+HGET · ② HGETALL(삽입순서) · ③ HDEL+HEXISTS · ④ HINCRBY · 갱신 시 신규 0 · HMGET/HKEYS/HVALS/HLEN · HINCRBYFLOAT · HSTRLEN · HSETNX · HMSET · TYPE=hash · WRONGTYPE 양방향 · 빈 해시 자동삭제
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(store·command 확장)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-HASH-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 STR · 자료형 불일치 WRONGTYPE
  - [x] Do NOT 위반 없음. LIST·EXP·STR·NET 회귀 없음
[2026-09-07 23:55] [DECISION] 요구사항 시트 REQ-HASH-FUNC-01 진행현황 기획중→테스트중.
[2026-09-07 23:56] [COMPLETE] REQ-HASH-FUNC-01 구현·검증 완료. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-SET-FUNC-01.
