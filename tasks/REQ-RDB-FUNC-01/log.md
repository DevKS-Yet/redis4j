# REQ-RDB-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline(이 단계 MainWorker=claude-main도 Orchestrator 내부 추론으로 대체) / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK(gradle·redis-cli 미설치).

**[임의 결정 5] 자체 스냅샷 포맷(RDB4J v1)** — Redis RDB 완전 호환 대신 독자 포맷 허용(요구서 명시). 레이아웃: `MAGIC("RDB4J") + VERSION(1)` 다음 opcode 스트림 — `SELECTDB(0xFE)+int`로 논리 DB 전환, `ENTRY(0x00)`마다 `expireAtMillis(long)·type(byte)·key(str)·value(type별)`, 끝에 `EOF(0xFF)`. 문자열/값은 `int 길이 + 원문`(바이너리 안전). DataInput/OutputStream 사용. 하위호환: 로드 시 version > 현재면 거부, 이하이면 수용.

**[임의 결정 6] 일관 스냅샷 = 락 아래 in-memory 직렬화, 디스크 쓰기 분리** — 스레드-per-connection 모델이라 fork(COW) 불가. SAVE·BGSAVE 모두 전역 락(Keyspace 모니터) 아래에서 바이트 배열로 직렬화(짧게 점유·일관성 확보) 후, SAVE 는 호출 스레드가 동기 쓰기(블로킹 — Redis SAVE 의미), BGSAVE 는 데몬 스레드가 디스크 쓰기(즉시 "+Background saving started" 반환). 손상 방지: 임시파일에 쓰고 `Files.move(ATOMIC_MOVE)` — 저장 중 크래시에도 기존 스냅샷 온전(③), 실패 시 REPLACE_EXISTING 폴백.

**[임의 결정 7] 기동 자동 로드는 명시 경로 생성자만 — 테스트 격리** — `RedisServer(int)`는 SAVE 대상 경로(dump.rdb4j)만 두고 기동 시 로드 안 함(기존 소켓 테스트/검증기 다수가 빈 상태를 기대). `RedisServer(int, Path)`가 기동 시 해당 파일 있으면 자동 로드. Main 은 후자로 dump.rdb4j 자동 로드 활성화. 로드는 accept·만료 스케줄러 시작 전에 수행(동시성 배제).

**[임의 결정 8] SAVE/BGSAVE/LASTSAVE 를 executeData 단일 경로에 연결 + 미열거 항목 제외** — command/ServerCommands 를 디스패처의 데이터 실행 경로(executeData) 말미에 두어 MULTI/EXEC 안에서도 동작. LASTSAVE 는 unix epoch 초. 로드 시 이미 만료된 키는 스킵. 선택 항목(자동 저장 조건 save-points, 설정파일)은 이번 범위 제외(후속). SAVE 는 `command/server/**` 대신 기존 평면 `command/` 배치(일관성).

## 액션 (표준 태그)
[2026-09-08 21:10] [APPROVAL] 사용자 일괄 승인 하에 REQ-RDB-FUNC-01 진행(inline).
[2026-09-08 21:10] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-08 21:35] [DECISION] 구현 — persistence/rdb/{RdbCodec(자체 포맷 직렬화/로드), RdbManager(SAVE·BGSAVE·LASTSAVE·loadIfExists·임시파일→원자 rename)} · store/Database.liveEntries · command/ServerCommands · CommandDispatcher(ctor에 RdbManager, executeData 말미 서버명령) · CommandCatalog.KNOWN(SAVE/BGSAVE/LASTSAVE) · server/RedisServer(RdbManager 배선·명시 경로 자동 로드·종료 정리) · Main(dump.rdb4j 자동 로드). JUnit RdbPersistenceTest 추가.
[2026-09-08 21:45] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyRdb PASS=22 FAIL=0 (artifacts/verify_output.txt) + 회귀 TX 27/0 · PUBSUB 18/0 · KEY 34/0 · ZSET 42/0 · SET 30/0 · HASH 31/0 · LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0 (총 301/0). 작업 디렉터리에 stray 스냅샷 없음(테스트 격리 확인):
  ① SAVE→파일 생성 · ② 재시작→GET/LRANGE/HGETALL/SMEMBERS/ZRANGE·PTTL(TTL)·다중 DB(db1) 복원 · ③ 임시파일 잔존 없음·재저장 후 온전 로드 · ④ 빈 데이터셋 저장/로드 · BGSAVE 백그라운드 파일 생성·로드 · LASTSAVE
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(persistence·command·server)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-RDB-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 자료형 · 임시파일→원자 rename · 자체 포맷+버전 헤더 · 일관 스냅샷(락)
  - [x] Do NOT 위반 없음. 서버 생성자·디스패처 변경에도 TX~NET 회귀 없음, cwd 오염 없음
[2026-09-08 21:46] [DECISION] 요구사항 시트 REQ-RDB-FUNC-01 진행현황 기획중→테스트중.
[2026-09-08 21:46] [COMPLETE] REQ-RDB-FUNC-01 구현·검증 완료. 재시작 데이터 보존 달성. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-AOF-FUNC-01.
