# REQ-PUBSUB-FUNC-01 실행 로그 (append-only)

## 임의 결정 사항 (사용자 일괄 승인 하에 Orchestrator가 결정)

**[임의 결정 1~4]** 이전 단계와 동일 — 성격 상향(기획중→코드 구현) / 워커 inline / codex-critic 정식 리뷰는 NFR-02 / 로컬 검증은 JDK(gradle·redis-cli 미설치).

**[임의 결정 5] 서버→클라이언트 비동기 푸시 = ClientOutput(연결별 동기화 쓰기)** — 발행자 스레드가 구독자 소켓에 직접 쓴다. 명령 응답(핸들러 스레드)과 메시지 푸시(발행자 스레드)가 같은 `ClientOutput` 모니터로 직렬화돼 RESP 프레임이 섞이지 않는다. 순환 의존 회피를 위해 `pubsub/MessageSink` 인터페이스를 두고 `server/ClientOutput`이 구현(pubsub→server 의존 없음).

**[임의 결정 6] PubSub 레지스트리 = 단일 인스턴스 모니터, 전송은 락 밖** — 채널/패턴 맵 조작을 `synchronized(this)`로 직렬화(요구서 "다중 스레드 동시성 안전"). PUBLISH 는 대상 스냅샷을 락 안에서 수집한 뒤 블로킹 소켓 쓰기는 락 밖에서 수행(락 점유 시간 최소화·데드락 회피). fire-and-forget — 전송 중 끊긴 연결은 무시.

**[임의 결정 7] 구독 모드 게이팅 + PING 배열형** — 구독 상태 연결은 SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE/PING/QUIT 만 허용, 그 외는 "ERR Can't execute '<cmd>': ... allowed in this context". 구독 모드 PING 은 RESP2 규약대로 `["pong", <arg|"">]` 배열 반환. PUBLISH/PUBSUB 도 구독 모드에서는 불허(Redis 규약). SUBSCRIBE 계열은 확인 메시지를 sink 로 자체 전송하고 디스패처엔 null 반환(추가 응답 없음) — ConnectionHandler 는 null 이면 쓰지 않음.

**[임의 결정 8] glob 공용화 + 미열거 항목 제외** — KEYS/SCAN 의 glob 매처를 `util/GlobMatcher`로 추출해 PSUBSCRIBE 패턴 매칭과 공용(DRY; KeyspaceCommands 는 위임하도록 갱신). NUMPAT 은 구독자 있는 고유 패턴 수. 미열거 RESET·SSUBSCRIBE(shard)·RESP3 push 타입은 제외. 메시지 payload 는 바이트 안전(byte[]), 채널·패턴은 UTF-8 String.

## 액션 (표준 태그)
[2026-09-08 01:50] [APPROVAL] 사용자 일괄 승인 하에 REQ-PUBSUB-FUNC-01 진행(inline).
[2026-09-08 01:50] [DECISION] 요청문 변환 + 위 임의 결정 1~8.
[2026-09-08 02:10] [DECISION] 구현 — pubsub/{MessageSink,Subscriber,PubSub} · server/ClientOutput · command/PubSubCommands(SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE/PUBLISH/PUBSUB CHANNELS·NUMSUB·NUMPAT) · ConnectionState(sink·subscriber·isSubscribed) · ConnectionHandler(ClientOutput·onDisconnect 자동 해제) · CommandDispatcher(구독 게이팅·PING 배열·pubsub 라우팅·onDisconnect) · util/GlobMatcher 추출(KeyspaceCommands 위임). JUnit PubSubCommandTest 추가.
[2026-09-08 02:15] [VERIFICATION] javac(Java 21) 컴파일 성공. VerifyPubSub PASS=18 FAIL=0 (artifacts/verify_output.txt) + 회귀 KEY 34/0 · ZSET 42/0 · SET 30/0 · HASH 31/0 · LIST 36/0 · EXP 24/0 · STR 30/0 · NET 7/0 (총 252/0):
  ① SUBSCRIBE→message 수신 · ② PSUBSCRIBE news.*→pmessage · ③ PUBLISH 수신자수(2/0) · ④ 연결 종료 후 NUMSUB 0(자동 해제) · 구독 모드 GET 제한 · PING 배열형 · UNSUBSCRIBE 후 정상 복귀 · PUBSUB CHANNELS/NUMSUB/NUMPAT
  Verification Checklist:
  - [x] output이 task.md 산출물 명세(pubsub·command·server·util)와 일치
  - [x] 파일 경로 실제 존재 (redis4j/src/**, tasks/REQ-PUBSUB-FUNC-01/**)
  - [x] task.md constraints 충족: 선행 NET · fire-and-forget · 구독 맵 동시성(인스턴스 모니터) · 허용 명령 제한
  - [x] Do NOT 위반 없음. glob 추출·ConnectionHandler 변경에도 KEY~NET 회귀 없음
[2026-09-08 02:16] [DECISION] 요구사항 시트 REQ-PUBSUB-FUNC-01 진행현황 기획중→테스트중.
[2026-09-08 02:16] [COMPLETE] REQ-PUBSUB-FUNC-01 구현·검증 완료. 서버→클라이언트 비동기 푸시 모델 도입. 정식 리뷰·CI는 REQ-CORE-NFR-02. 다음: REQ-TX-FUNC-01.
