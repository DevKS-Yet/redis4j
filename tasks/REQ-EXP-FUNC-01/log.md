# REQ-EXP-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** REQ-NET/STR와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02로 보류 / 로컬 검증은 JDK(javac+소켓 하니스, gradle·redis-cli 미설치).

**[임의 결정 5] 능동 만료 = 데몬 스케줄러 + 사이클당 상한** — `ScheduledExecutorService`(daemon) 100ms 주기로 `synchronized(db)` 하에 `activeExpireCycle` 실행. 한 사이클은 만료 후보를 **최대 100개만 검사**(부하 제한 — [제약]). daemon이라 JVM 종료를 막지 않음. lazy(수동) 만료는 STR 단계 `Database.get`에 이미 있어 재사용.

**[임의 결정 6] TTL 초 반올림 = (ms+500)/1000** — Redis TTL 규약 근사(반올림). PTTL은 ms 그대로. 없는 키 -2, 만료 없음 -1.

**[임의 결정 7] EXPIRE 결과가 과거면 즉시 삭제** — EXPIRE/EXPIREAT 계산 결과 시각이 현재 이하이면 키를 즉시 삭제하고 :1(키 존재 시) 반환(Redis 규약). 없는 키는 :0. Redis 7의 EXPIRE 옵션(NX/XX/GT/LT)은 요구 목록 밖이라 미구현(후속).

**[임의 결정 8] 명령 라우팅 조정** — 자료형 명령 클래스가 늘어나므로, `StringCommands.execute`가 미처리 명령에 **null 반환**하도록 바꾸고, `CommandDispatcher`가 String→Expire 순으로 시도한 뒤 둘 다 null이면 unknown 에러를 낸다(unknown 메시지는 원본 명령 토큰 사용). NET/STR 회귀로 무해 확인.

**[임의 결정 9] 검증용 rawSize 노출** — 능동 만료(④)를 lazy와 분리해 판정하려면, 키를 **접근하지 않고** 맵이 스스로 줄었는지 봐야 한다. `Database.rawSize()`(purge 없이 맵 크기)를 추가해, 신선한 서버에 만료 키 다수를 넣고 미접근 상태로 rawSize 감소를 확인.

## 액션 (표준 태그)
[2026-09-07 22:45] [APPROVAL] 사용자 일괄 승인 하에 REQ-EXP-FUNC-01 진행(inline).
[2026-09-07 22:45] [DECISION] 요청문 변환 + 위 임의 결정 1~9.
[2026-09-07 23:00] [DECISION] 구현 — Database(setExpireAt·ttlMillis·persist·activeExpireCycle·rawSize) · ExpireCommands(EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/TTL/PTTL/PERSIST) · 디스패처 String→Expire 라우팅 · RedisServer 데몬 만료 스케줄러(100ms). JUnit ExpireCommandTest 추가.
[2026-09-07 23:05] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyExp PASS=24 FAIL=0 (artifacts/verify_output.txt) + STR 회귀 30/0 + NET 회귀 7/0:
  ① PEXPIRE + lazy 만료 · ② TTL -2/-1·PTTL -1 · ③ PERSIST(TTL→-1·키 유지·재차 :0) · SET EX 후 TTL~100(STR ④ 종단 확인) · EXPIRE absent→:0 · PEXPIREAT 과거→삭제 · ④ 능동 만료(200키 미접근 rawSize 200→0)·처리 중 PING 정상
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(store·command·server 확장)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-EXP-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 STR · 서버 시계 ms 정밀도 · 능동 만료 사이클당 최대 100개 검사(부하 제한)
  - [x] Do NOT 위반 없음. STR·NET 회귀 없음
[2026-09-07 23:05] [DECISION] 요구사항 시트 REQ-EXP-FUNC-01 진행현황 기획중→테스트중.
[2026-09-07 23:06] [COMPLETE] REQ-EXP-FUNC-01 구현·검증 완료. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-LIST-FUNC-01.
