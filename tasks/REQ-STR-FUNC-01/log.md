# REQ-STR-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1] 성격 상향** — 시트 진행현황 `기획중`의 기계적 매핑은 분석·요약이나, "다음 단계 진행" 지시 + 실행 검증 필요 → 코드 구현. (REQ-NET-FUNC-01과 동일 패턴, learnings 등재됨.)

**[임의 결정 2] 워커 inline 실행** — [Worker Settings]는 codex-main/codex-critic/datatype-command-expert 지정이나, spawn 대신 Orchestrator가 datatype-command-expert 도메인 규약(반환 타입·엣지·WRONGTYPE 규격 준수)을 적용해 직접 구현.

**[임의 결정 3] codex-critic 정식 리뷰 보류** → REQ-CORE-NFR-02. 자동 스모크로 1차 확인.

**[임의 결정 4] 로컬 검증은 JDK(javac/java)** — gradle·redis-cli 미설치. build 설정은 유지, 검증은 순수 JDK 소켓 하니스.

**[임의 결정 5] 명령 인자 파이프라인 String→byte[] 리팩터** — [제약] "값은 바이트 안전"을 지키려 RespDecoder를 `List<byte[]>` 반환으로 바꾸고 CommandDispatcher·ConnectionHandler를 맞춤. **키는 UTF-8 String**(제약이 '값'의 바이트안전만 요구). 부수 효과로 ECHO/PING 메시지도 바이트 안전해짐(NET 계층 개선). NET 회귀 검증으로 무해 확인.

**[임의 결정 6] 만료 범위 분리** — 이번 단계는 SET의 EX/PX/EXAT/PXAT/KEEPTTL 저장 + **lazy(수동) 만료**(만료 키 접근 시 삭제)만 구현. TTL/PTTL/EXPIRE/PERSIST 명령과 **능동(주기 샘플링) 만료**는 REQ-EXP-FUNC-01로 이관. 완료조건 ④는 `SET k v PX 100` 후 만료로 부분 검증하고, TTL 명령 확인은 EXP 단계에서.

**[임의 결정 7] 동시성=Database 단일 락** — 서버가 연결당 가상 스레드로 동시 실행되므로, 데이터 명령을 `synchronized(db)`로 직렬화해 명령 원자성 보장(Redis 단일스레드 실행 모델과 동일 의미). NFR-01에서 공식화/최적화 예정.

**[임의 결정 8] WRONGTYPE 검증 방식** — 타입 생성 명령(LPUSH 등)이 아직 없어 완료조건 ③을 사용자 명령만으로는 못 만든다. RedisType enum에 LIST/HASH/SET/ZSET을 미리 정의하고, verifier가 비-STRING 값을 `Database`에 직접 주입 후 GET→WRONGTYPE로 **기계 수준 검증**. LIST 단계에서 종단 재확인.

## 액션 (표준 태그)

[2026-09-07 22:15] [APPROVAL] 사용자 일괄 승인 하에 REQ-STR-FUNC-01 진행(inline).
[2026-09-07 22:15] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-07 22:30] [DECISION] redis4j 구현 — store(RedisType·RedisObject·StringObject·Database lazy만료) · command(StringCommands 19종) · protocol byte[] 리팩터 · server DB 연결. JUnit StringCommandTest 추가.
[2026-09-07 22:34] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyStr PASS=30 FAIL=0 (artifacts/verify_output.txt) + NET 회귀 Verify PASS=7 FAIL=0:
  ① SET/GET · ② INCR/DECRBY/비정수 에러 · ③ 비-STRING GET→WRONGTYPE(주입)·TYPE · ④ SET PX + lazy 만료 · APPEND/STRLEN/GETSET/SETNX/MSET/MGET/TYPE/DEL/EXISTS · 바이트 안전 · 동시 INCR 원자성
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(store·command 소스)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-STR-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 NET 위에 구축 · 값 바이트 안전 · INCR 비정수 에러 · 내부 인코딩 모방 없음 · TTL/EXPIRE 명령은 EXP 단계로 이관
  - [x] Do NOT 위반 없음. NET 회귀 없음(PASS 7/0)
[2026-09-07 22:34] [DECISION] 요구사항 시트 REQ-STR-FUNC-01 진행현황 기획중→테스트중.
[2026-09-07 22:35] [COMPLETE] REQ-STR-FUNC-01 구현·검증 완료. 정식 리뷰·CI는 REQ-CORE-NFR-02, TTL 명령 종단 확인은 REQ-EXP-FUNC-01.
