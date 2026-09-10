// 이 기계에는 JDK 17 하나만 있다. 아래 플러그인이 없으면 Java 21 툴체인을 못 찾아 빌드가 죽는다.
// 이 플러그인이 붙어 있으면 Gradle 이 필요한 JDK 를 직접 받아 온다.
plugins {
    // 🔴 0.9.0 은 Gradle 9.3 에서 IBM_SEMERU 로 죽는다 (2026-09-10 확인). 버전을 내리지 말 것.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hindsight"

// ── v0 — 고리 전체. 바이트코드를 안 쓴다 (설계 §2) ──────────────────────────
include("hindsight-model")             // 기록 자료 구조. Java 17. 🔴 Jackson 애너테이션 금지
include("hindsight-core")              // 기록 읽기·쓰기·가명화
include("hindsight-recorder-simple")   // v0 기록기: Filter + DataSource 감싸기
include("hindsight-replay")            // 재생 · 오라클 · 테스트 생성
include("hindsight-guard")             // 🔴 패치 경로 검사. 파일을 쓰는 유일한 통로
include("hindsight-brain")             // 진단 · 채점 고리 · PR
include("hindsight-cli")               // hs 명령어
include("hindsight-testkit")           // 기록 픽스처와 테스트 도우미
include("demo-app")                    // 🔴 진짜 스프링 서비스. 소품이 아니다 (설계 §3-4)

// ── v1 — 기록기를 진짜 에이전트로 갈아끼운다 ───────────────────────────────
include("hindsight-agent-boot")        // 🔴 부트스트랩에 올라가는 얇은 껍데기. Java 17, 의존성 없음
include("hindsight-agent")             // premain · ByteBuddy(셰이딩) · 링 버퍼 · 방아쇠

// ── v3 — 열어주기 ─────────────────────────────────────────────────────────
include("hindsight-mcp")               // 코딩 에이전트용 통로
include("hindsight-server")            // 저장소 · REST · 화면
