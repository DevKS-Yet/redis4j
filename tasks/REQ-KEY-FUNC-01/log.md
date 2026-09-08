# REQ-KEY-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK(gradle·redis-cli 미설치).

**[임의 결정 5] 다중 DB = Keyspace(기본 16) 도입, 전역 락 이동** — SELECT/SWAPDB 지원을 위해 `Keyspace`(Database[16]) 신설. 명령 단위 원자성의 전역 락을 단일 `Database`→`Keyspace` 인스턴스로 이동(SWAPDB·FLUSHALL 처럼 여러 DB를 건드리는 명령까지 하나의 모니터로 직렬화). 능동 만료도 Keyspace 모니터에서 전 DB 순회.

**[임의 결정 6] 기존 6개 자료형 명령 클래스 무수정** — CommandDispatcher가 DB별 핸들러 세트(String/Expire/List/Hash/Set/ZSet)를 미리 구성하고 연결의 `dbIndex`로 라우팅. SWAPDB는 참조가 아닌 **DB 내용(맵)** 을 교환하므로 미리 바인딩한 핸들러가 그대로 유효(Redis SWAPDB 의미론과 일치). 자료형 클래스 시그니처·구현 불변 → 회귀 위험 최소화.

**[임의 결정 7] KeyspaceCommands 는 command/ 평면 배치** — 요구서 [작업 대상]의 `command/keyspace/**` 서브패키지 대신 기존 6개 자료형 명령 클래스와 동일하게 `command/` 평면에 둠(일관성·디스패처 배선 단순). DEL·EXISTS·TYPE 은 STR 단계 StringCommands 담당 유지, 신규는 UNLINK(DEL 별칭)만 키공간에서 처리.

**[임의 결정 8] SCAN 커서 = 정렬 키 오프셋 / DBSIZE = rawSize / COPY·MOVE 제외** — SCAN 은 매 호출 살아있는 키를 정렬해 커서(인덱스 오프셋)로 순회(약한 일관성 허용 — 순회 중 삭제 시 스킵/중복 가능, 요구서 허용). COUNT 는 검사 창(기본 10), MATCH/TYPE 는 사후 필터. DBSIZE 는 O(1) rawSize(Redis 의미론). glob 은 `*`·`?`·`[...]`(^부정·a-z범위·\이스케이프) 자체 구현. 선택 항목 COPY/MOVE 는 이번 범위 제외(후속).

**[부수 변경] 기존 JUnit 락 대상 갱신** — StringCommandTest·ExpireCommandTest 의 `synchronized(server.database())`(=db0 모니터)를 신규 전역 락 `server.keyspace()`로 변경(디스패처·능동만료와 동일 모니터 유지, 정합성 보존). `server.database()`는 db(0) 반환으로 호환 유지, `server.keyspace()` 접근자 추가. 이전 단계 커밋된 JDK 하니스는 미수정(컴파일·통과 유지).

## 액션 (표준 태그)
[2026-09-08 01:10] [APPROVAL] 사용자 일괄 승인 하에 REQ-KEY-FUNC-01 진행(inline).
[2026-09-08 01:10] [DECISION] 요청문 변환 + 위 임의 결정 1~8 및 부수 변경.
[2026-09-08 01:30] [DECISION] 구현 — store/Keyspace(Database[16], 전역 락) · Database 확장(liveKeys·randomKey·rename·clear·swapContentsWith, map non-final) · server/ConnectionState(dbIndex) · command/KeyspaceCommands(KEYS glob·SCAN·RANDOMKEY·DBSIZE·RENAME/RENAMENX·FLUSHDB/FLUSHALL·SELECT·SWAPDB·UNLINK) · CommandDispatcher(Keyspace 락 + DB별 핸들러 세트 + 체인 말미 keyspace) · RedisServer(Keyspace 배선, 만료 전 DB 순회, keyspace()/database() 접근자). JUnit KeyCommandTest 추가.
[2026-09-08 01:35] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyKey PASS=34 FAIL=0 (artifacts/verify_output.txt) + 회귀 ZSET 42/0 · SET 30/0 · HASH 31/0 · LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0 (총 234/0):
  ① MSET+KEYS *(순서무관 집합비교) · ② SCAN 0 COUNT 3 전체 순회 무손실 · ③ SELECT 1 격리(+SWAPDB 내용교환) · ④ FLUSHDB→DBSIZE 0 · glob(?·[ae]·*) · RENAME/RENAMENX · RANDOMKEY · UNLINK · SCAN MATCH/TYPE · SELECT 범위/정수 검증
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(store/Keyspace·command/KeyspaceCommands 등)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-KEY-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 STR · SCAN 커서 순회 무손실 · glob 규약 · SELECT 격리 · COPY/MOVE 제외
  - [x] Do NOT 위반 없음. ZSET·SET·HASH·LIST·EXP·STR·NET 회귀 없음(전역 락 이동에도 무손상)
[2026-09-08 01:36] [DECISION] 요구사항 시트 REQ-KEY-FUNC-01 진행현황 기획중→테스트중.
[2026-09-08 01:36] [COMPLETE] REQ-KEY-FUNC-01 구현·검증 완료. 다중 논리 DB(16) 도입. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-PUBSUB-FUNC-01.
