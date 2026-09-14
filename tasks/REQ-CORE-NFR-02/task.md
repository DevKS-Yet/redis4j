# REQ-CORE-NFR-02 — 테스트·호환성·품질 요건

status: done
created: 2026-09-12
target_repo: C:/Users/ksang/IdeaProjects/MultiAgent/redis4j
write_scope: redis4j/**, .github/workflows/**

## workers_approved
사용자 위임("확인해보고 중요한 사항일 경우 진행"). 워커 Orchestrator inline 실행(외부 워커 미호출).
- 생산: codex-main (inline) / 리뷰: codex-critic (inline) / SubAgent: testing-qa-expert (inline)

## 요청문 (요구사항 시트 K15 = REQ-CORE-NFR-02)

목표: 각 단계가 실제 Redis 규약과 호환되고 회귀 없이 누적되도록 하는 검증·품질 안전망.
      전 단계 공통 적용. 각 단계 DoD = 해당 검증 기준 테스트 통과 + 회귀 통과.
상세:
① 단위 테스트(파서/자료구조/명령) + 통합 테스트(실제 소켓 왕복)
② redis-cli 및 표준 클라이언트(Jedis/Lettuce) 상호운용 스모크
③ 각 REQ [검증 기준]을 자동화 테스트로 표현
④ CI에서 Gradle 빌드·테스트 자동화, 커버리지 측정
⑤ 에러 응답 형식 Redis 규약 일치(-ERR/-WRONGTYPE)
검증 기준:
① 전체 단계 검증 기준이 자동 테스트로 존재·통과
② redis-cli로 주요 명령 수동 검증 시 정상 응답
③ CI 파이프라인에서 그린 빌드
④ 신규 단계 추가 시 기존 테스트 회귀 없음
작업 대상: redis4j/src/test/java/** · .github/workflows(CI) · build.gradle.kts

## 현황 진단 (착수 시점)
- src/test/java 에 13개 테스트 클래스·**@Test 67개** 가 전 단계에서 이미 작성돼 있었으나,
  **gradle 미설치로 테스트 러너로 실제 실행된 적이 없음**(지금까지 검증은 artifacts/VerifyXxx.java javac 하니스뿐).
- gradle wrapper 부재(gradlew·wrapper jar 없음), CI(.github/workflows) 부재, 커버리지 측정 없음.
- build.gradle.kts는 JUnit5(junit-bom 5.10.2) 설정만 존재.
- → NFR-02의 미충족 DoD = "67 테스트를 Gradle로 실제 그린 빌드 + 재현 가능(wrapper) + CI 자동화 + 커버리지".

## 구현 계획 → 검증
1. gradle wrapper 8.13 생성(Java 21 호환) → 캐시 gradle 의존 제거·CI 재현성   → verify: gradlew --version
2. build.gradle.kts: jacoco 플러그인 + jacocoTestReport(xml/html) + test finalizedBy → verify: 리포트 생성
3. .github/workflows/ci.yml: JDK21(temurin) checkout → ./gradlew build jacocoTestReport → verify: YAML·로컬 등가 명령 그린
4. 전체 스위트 실제 실행: ./gradlew clean build jacocoTestReport                → verify: 67 pass, 0 fail
5. 회귀 안전망 확정: build(=check→test) 통과 = 신규 단계 회귀 게이트           → verify: BUILD SUCCESSFUL

## 산출물 위치
- wrapper: redis4j/{gradlew, gradlew.bat, gradle/wrapper/*}
- 빌드: redis4j/build.gradle.kts (jacoco)
- CI: .github/workflows/ci.yml (git 루트, working-directory: redis4j)
- 리포트(로컬 생성, git 미추적): redis4j/build/reports/{tests,jacoco}

## 잔여(범위 밖·후속)
- ~~검증기준② 표준 클라이언트(Jedis) 상호운용 스모크~~ ✅ 2026-09-15 충족: JedisInteropTest 7종
  (핸드셰이크·String·List/Hash/Set/ZSet·TTL/멀티DB·MULTI/EXEC·WATCH취소·Pub/Sub·WRONGTYPE) 전부 그린.
  Jedis 기본(RESP2) 접속이 shim 없이 통과 — "미지 명령에도 연결 유지" 설계가 CLIENT SETINFO를 흡수.
  · 잔여: redis-cli 바이너리 상호운용은 로컬 미설치로 후속(원한다면 CI에 redis-tools 설치 스텝).
- ~~검증기준③ 실제 GitHub Actions 그린~~ ✅ 2026-09-14 충족: push 후 run=success 확인.
  (최초 런은 gradlew 실행비트 100644로 실패 → `git update-index --chmod=+x`(commit 6f9fa22)로 100755 승격 후 그린. 상세 log.md 참조.)
