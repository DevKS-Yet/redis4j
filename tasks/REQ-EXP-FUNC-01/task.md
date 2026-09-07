# REQ-EXP-FUNC-01 — 키 만료(TTL) 서브시스템

status: done
created: 2026-09-07
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**

## workers_approved
사용자 일괄 승인. 워커 Orchestrator inline 실행.
- 생산: codex-main (inline) / 리뷰: codex-critic (보류→NFR-02) / SubAgent: datatype-command-expert

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-EXP-FUNC-01

목표: 키에 수명을 부여하고, 만료된 키가 조회 시(수동)와 주기적 샘플링(능동)으로 제거되는
      만료 서브시스템을 완성한다. SET의 EX/PX 옵션(STR 단계)과 일관 동작한다.
완료 조건 :
① SET k v; EXPIRE k 1; 1.1초 후 GET k → (nil)
② TTL(없는 키) → -2, TTL(영구 키) → -1
③ PERSIST 후 TTL → -1, 키 유지
④ 대량 만료 키 존재 시에도 다른 명령 지연이 무시할 수준(능동 만료가 부하 제한 하에 동작)
성격: 코드 구현   ([임의 결정 1])
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 확장 + server 능동 만료)
제약: 선행 REQ-STR-FUNC-01 / 시간 기준 서버 단조 시계, 밀리초 정밀도 /
      능동 만료가 명령 처리 지연을 유발하지 않도록 사이클당 작업량 제한

## 구현 계획 → 검증
1. Database: setExpireAt·ttlMillis·persist·activeExpireCycle(bounded)·rawSize(검증용)  → verify: 단위
2. command: ExpireCommands — EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/TTL/PTTL/PERSIST         → verify: ①②③
3. dispatch 라우팅: String→Expire 순, 미처리 시 unknown 에러(StringCommands.execute null 반환) → verify: 회귀
4. server: 데몬 스케줄러 100ms 주기 능동 만료(사이클당 상한)                              → verify: ④
5. 완료조건 ①②③④ + 회귀(STR/NET)                                                       → verify: 하니스 all PASS

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store,command,server}
- 검증 하니스: tasks/REQ-EXP-FUNC-01/artifacts/VerifyExp.java + verify_output.txt
- 로그: tasks/REQ-EXP-FUNC-01/log.md
