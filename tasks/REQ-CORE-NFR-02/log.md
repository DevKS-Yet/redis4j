# REQ-CORE-NFR-02 실행 로그 (append-only)

## 임의 결정 사항 (사용자 위임 "중요하면 진행" 하에 Orchestrator 결정)

**[결정 1] 워커 inline** — 이전 단계와 동일. 외부 워커(codex/gemini) 미호출, Orchestrator 내부 추론 + Claude Code 도구로 수행.

**[결정 2] gradle 부트스트랩 = 캐시 배포판(8.13)** — 시스템 gradle·gradlew 부재였으나 `~/.gradle/wrapper/dists` 에 6.9.1/7.6.4/8.13/9.5.1 캐시 존재. Java 21 호환 안정 버전인 8.13 로 wrapper 생성 → 이후 캐시 의존 제거(재현성).

**[결정 3] 검증기준② 실 클라이언트 스모크는 후속** — redis-cli/Jedis 로컬 미설치. RESP2 실소켓 왕복은 ServerIntegrationTest 로 대체 검증. 실 클라이언트 상호운용 스모크는 범위 밖(후속).

**[결정 4] 검증기준③ 실제 GitHub Actions 그린은 push 소관** — 로컬에서 CI 등가 명령 `./gradlew build`(=check→test) 그린으로 실증. 워크플로 YAML 은 작성·검토.

## 액션 (표준 태그)
[2026-09-12 22:26] [APPROVAL] 사용자 위임("확인해보고 중요한 사항일 경우 진행"). NFR-02 = 시트 K15·우선순위 High·유일 '기획중' → 중요 판정, 진행.
[2026-09-12 22:26] [DECISION] 현황 진단 — 테스트 67개(@Test) 이미 존재하나 gradle 부재로 미실행. wrapper·CI·커버리지 부재가 갭.
[2026-09-12 22:27] [ACTION] 캐시 gradle 8.13 으로 전체 테스트 최초 실행: tests=67 fail=0 err=0 skip=0 (13 classes) — 프로젝트 최초 테스트 러너 그린 빌드.
[2026-09-12 22:27] [ACTION] gradle wrapper 8.13 생성(gradlew·gradlew.bat·gradle/wrapper/{jar,properties}).
[2026-09-12 22:28] [ACTION] build.gradle.kts: jacoco 플러그인 + jacocoTestReport(xml/html) + test testLogging/finalizedBy.
[2026-09-12 22:28] [ACTION] .github/workflows/ci.yml 생성(git 루트, working-directory: redis4j, temurin JDK21, ./gradlew build jacocoTestReport, 리포트 업로드).
[2026-09-12 22:29] [VERIFICATION] wrapper 로 end-to-end 재실행 `./gradlew clean build jacocoTestReport` → BUILD SUCCESSFUL, 67 tests ALL PASSED.
  커버리지(jacoco): LINE 64.1% / INSTRUCTION 65.8% / BRANCH 48.8% / METHOD 83.4% / CLASS 94.4%.
  Verification Checklist:
  - [x] 검증기준① 전체 단계 검증 기준 자동 테스트 존재·통과 (67/67, NET~AOF~NFR-01 전 단계 커버)
  - [x] 검증기준④ 회귀 없음 — build=check 게이트로 전 클래스 그린
  - [x] 검증기준③ CI 그린 = 로컬 CI-등가 명령(./gradlew build) 실증 (실제 Actions 는 push 소관)
  - [~] 검증기준② redis-cli/Jedis 상호운용 = ServerIntegrationTest 실소켓 왕복으로 대체, 실 클라이언트 스모크는 후속
  - [x] output = task.md 산출물 명세(wrapper·build.gradle.kts·ci.yml)와 일치, 경로 실존
  - [x] Do NOT 위반 없음 / 외부 프레임워크 미도입(jacoco·junit 은 표준 테스트 인프라)
[2026-09-12 22:29] [DECISION] 요구사항 시트 REQ-CORE-NFR-02(K15) 진행현황 기획중→테스트중.
[2026-09-12 22:29] [COMPLETE] REQ-CORE-NFR-02 구현·검증 완료. 67 테스트 실제 그린 빌드 + wrapper 재현성 + CI 자동화 + 커버리지. **전 15개 요구사항 단계 완료.**
[2026-09-14 23:22] [VERIFICATION] 실제 GitHub Actions 그린 확인. 최초 push(4b3f817) 런은 failure — 원인은 코드가 아니라 redis4j/gradlew git 모드 100644(비실행) → ubuntu-latest에서 ./gradlew Permission denied(exit 126, 테스트 실행 전 사망). 로컬(Windows)은 gradlew.bat 사용·실행비트 개념 없어 clean build 67/67 그린이라 미검출. 조치: git update-index --chmod=+x → 100755, commit 6f9fa22 push. 재실행 run=success(build-test 전 스텝 success, "Build & test (with coverage)" 통과). → 검증기준③ [x]로 승격(로컬 등가가 아닌 실 Actions 그린).
[2026-09-15 00:27] [VERIFICATION] 검증기준② 실 클라이언트 상호운용 스모크 충족. Jedis 5.1.0(testImplementation) + JedisInteropTest 7종 작성. 실제 Jedis 라이브러리로 접속 핸드셰이크(CLIENT SETINFO 등 미지 명령 포함)를 거쳐 RESP2 왕복 실증: connects_and_pings·string·list/hash/set/zset·expiry+멀티DB(SELECT)·MULTI/EXEC+WATCH취소·Pub/Sub·WRONGTYPE. 서버 shim 불필요 — 기존 "미지 명령에도 연결 유지" 동작이 Jedis 부트스트랩을 흡수(default RESP2라 HELLO 미전송). 전체 clean build 회귀: 74 tests(67+Jedis7) pass, 0 fail/err/skip. 잔여: redis-cli 바이너리 상호운용은 로컬 미설치로 후속.
