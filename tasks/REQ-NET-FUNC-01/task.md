# REQ-NET-FUNC-01 — RESP2 프로토콜 TCP 서버 기반

status: done
created: 2026-09-07
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**   (현재 저장소 내부 하위 모듈 — 외부 repo 아님)

## workers_approved

사용자가 본 요청에서 "승인이나 결정이 필요한 내용은 우선 진행"하라고 **일괄 승인**함.
- 생산: claude-main  (실제로는 Orchestrator inline 실행 — 아래 [임의 결정 2] 참조)
- 리뷰: codex-critic (이번 실행에서는 보류 — [임의 결정 3])
- SubAgent: server-core-expert (도메인 규약을 Orchestrator가 적용, 별도 spawn 안 함)

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-NET-FUNC-01

목표: RESP2(REdis Serialization Protocol) 기반 TCP 서버 기반 계층을 구축한다.
      Java 21 소켓 서버가 기동되어 다중 클라이언트를 동시에 처리하고, RESP2 요청을
      파싱·응답을 직렬화하며, PING/ECHO 왕복이 검증되는 상태. 이후 모든 명령이 이 계층 위에 올라간다.
완료 조건 :
① redis-cli -p 6379 ping → PONG
② 여러 클라이언트 동시 접속 시 상호 간섭 없이 각각 응답
③ redis-cli echo "hi" → "hi"
④ 미지원 명령 입력 시 "-ERR unknown command" 형식 응답, 연결 유지
성격: 코드 구현   (원칙상 기획중→분석·요약이나 [임의 결정 1]로 상향)
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=claude-main, 리뷰=codex-critic, SubAgent=server-core-expert
산출물: 소스 코드 (redis4j/ Gradle 프로젝트)
제약: Java 21 + Virtual Threads + Gradle(Kotlin DSL) 전제 / 외부 네트워크 프레임워크(Netty 등)
      도입 금지 — 표준 java.net·java.io·java.util.concurrent 직접 구현 / RESP3 금지(RESP2만) /
      잘못된 프로토콜 입력에 서버가 중단되지 않을 것 / 데이터 저장 로직은 이번 범위 밖(STR 단계)

## 구현 계획 → 검증

1. Gradle(Kotlin DSL) 프로젝트 골격 + 진입점  → verify: 소스 트리·설정 존재
2. RESP2 디코더/인코더                         → verify: 단위 왕복(array/bulk/simple/error)
3. 서버 accept 루프 + Virtual Thread(연결별)    → verify: 동시 접속 스모크
4. 명령 디스패처 + PING/ECHO/COMMAND/QUIT       → verify: 완료조건 ①③④
5. graceful shutdown                           → verify: 종료 시 소켓·스레드 정리
6. [검증 기준] ①②③④ 자동 스모크 통과          → verify: Verify 하니스 all PASS

## 산출물 위치
- 소스: `redis4j/` (Gradle 프로젝트)
- 검증 하니스: `tasks/REQ-NET-FUNC-01/artifacts/Verify.java`
- 검증 로그: `tasks/REQ-NET-FUNC-01/artifacts/verify_output.txt`
- 결정·행동 로그: `tasks/REQ-NET-FUNC-01/log.md`
