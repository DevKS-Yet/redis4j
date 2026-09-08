# REQ-PUBSUB-FUNC-01 — 발행/구독 메시징

status: done
created: 2026-09-08
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: server-core-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-PUBSUB-FUNC-01

목표: 채널 기반 발행/구독 메시징을 제공한다. 구독 연결은 서버가 미는 메시지를 수신하며
      패턴 구독(glob)을 지원한다. 서버→클라이언트 비동기 푸시 모델을 처음 도입.
완료 조건 :
① A가 SUBSCRIBE ch; B가 PUBLISH ch hi → A가 message 수신
② PSUBSCRIBE news.* 구독이 news.tech 발행 수신(pmessage)
③ PUBLISH 반환값 = 수신 구독자 수
④ 구독 중 연결 끊기면 서버 구독 목록에서 제거
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=server-core-expert
산출물: 소스 코드 (redis4j/ pubsub·command·server 확장)
제약: 선행 REQ-NET-FUNC-01 / 메시지 전달은 fire-and-forget(영속·보장 없음) /
      다중 스레드 구독 맵 동시성 안전 / 구독 상태 연결은 허용 명령 제한(subscribe/unsubscribe/ping/quit)

## 구현 계획 → 검증
1. pubsub/MessageSink(출구 인터페이스) · Subscriber(채널·패턴 집합+sink)                          → verify: 단위
2. pubsub/PubSub(채널·패턴 레지스트리, 인스턴스 모니터 동시성, 락 밖 전송)                          → verify: ①②③
3. server/ClientOutput(연결별 동기화 쓰기, MessageSink 구현) — 응답·푸시 프레임 직렬화             → verify: 프레이밍
4. command/PubSubCommands — SUBSCRIBE/UNSUBSCRIBE·PSUBSCRIBE/PUNSUBSCRIBE·PUBLISH·PUBSUB          → verify: ①②③
5. ConnectionState(sink·subscriber·isSubscribed) · ConnectionHandler(ClientOutput·onDisconnect)  → verify: ④
6. dispatch — 구독 모드 명령 제한 게이팅·PING 배열형·pubsub 라우팅(null=자체 전송)                 → verify: 제한
7. util/GlobMatcher 추출(KEYS/SCAN 와 PSUBSCRIBE 공용)                                             → verify: 회귀
8. 완료조건 ①②③④ + 게이팅/PING/PUBSUB 메타 + 회귀(KEY/ZSET/…/NET)                                → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{pubsub/{MessageSink,Subscriber,PubSub}, command/PubSubCommands,
  command/CommandDispatcher, server/{ClientOutput,ConnectionState,ConnectionHandler}, util/GlobMatcher}
- 검증 하니스: tasks/REQ-PUBSUB-FUNC-01/artifacts/VerifyPubSub.java + verify_output.txt
- JUnit: redis4j/src/test/java/redis4j/PubSubCommandTest.java
- 로그: tasks/REQ-PUBSUB-FUNC-01/log.md
