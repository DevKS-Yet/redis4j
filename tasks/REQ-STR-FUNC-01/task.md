# REQ-STR-FUNC-01 — String 자료형과 중앙 키 저장소

status: done
created: 2026-09-07
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**   (현재 저장소 내부 하위 모듈)

## workers_approved

사용자 일괄 승인("결정 필요분 우선 진행 + 로그화"). 워커는 Orchestrator inline 실행.
- 생산: codex-main  (inline)
- 리뷰: codex-critic (보류 → NFR-02)
- SubAgent: datatype-command-expert (도메인 규약 Orchestrator 적용)

## 요청문 (요구사항_TASK_전환 규칙 적용)

REQ-STR-FUNC-01

목표: 문자열 값의 저장·조회·수치 연산을 제공하고, 모든 자료형이 공유하는 중앙 키 공간(딕셔너리)
      자료구조를 확립한다. redis4j의 핵심 KV 동작이 성립하는 상태.
완료 조건 :
① SET foo bar → OK, GET foo → "bar"
② SET n 10; INCR n → (integer) 11
③ 비-String 키에 GET → WRONGTYPE 에러
④ SET k v (EX/PX) 후 만료 동작 — TTL 명령 확인은 EXPIRE 단계에서(본 단계는 SET EX/PX 저장 + lazy 만료)
성격: 코드 구현   ([임의 결정 1] 기획중→상향)
target_repo: redis4j
write_scope: redis4j/**
워커: 생산=codex-main, 리뷰=codex-critic, SubAgent=datatype-command-expert
산출물: 소스 코드 (redis4j/ store·command 계층)
제약: 선행 REQ-NET-FUNC-01 / 값은 바이트 안전(binary-safe) / INCR 대상이 정수 아니면
      "value is not an integer or out of range" / Redis 내부 인코딩(int/embstr) 모방은 범위 밖 /
      TTL·EXPIRE·PERSIST 명령과 능동 만료는 REQ-EXP-FUNC-01 범위

## 구현 계획 → 검증
1. store: RedisType·RedisObject·StringObject·Entry·Database(중앙 키공간, lazy 만료)  → verify: 단위
2. 명령 인자 파이프라인 String→byte[] 리팩터(값 바이트 안전)                          → verify: NET 회귀 + 바이트안전
3. command: SET(NX/XX/EX/PX/KEEPTTL/GET)·GET·GETSET·GETDEL·APPEND·STRLEN            → verify: ①④
4. INCR/DECR/INCRBY/DECRBY/INCRBYFLOAT (정수·부동소수, 비정수 에러)                  → verify: ②
5. MSET/MGET/SETNX/MSETNX·DEL(multi)·EXISTS(multi)·TYPE                            → verify: 다건
6. 명령 원자성: Database 단일 락 직렬화                                              → verify: 동시 INCR
7. WRONGTYPE 타입체크                                                              → verify: ③(주입)

## 산출물 위치
- 소스: redis4j/src/main/java/redis4j/{store,command,protocol,server}
- 검증 하니스: tasks/REQ-STR-FUNC-01/artifacts/VerifyStr.java
- 검증 로그: tasks/REQ-STR-FUNC-01/artifacts/verify_output.txt
- 결정·행동 로그: tasks/REQ-STR-FUNC-01/log.md
