// 🔴 0.9.0 은 Gradle 9.3 에서 IBM_SEMERU 로 죽는다 (2026-09-10 확인). 버전을 내리지 말 것.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hindsight"

// ⚠️ 여기에는 «지금 파일이 들어 있는» 모듈만 적는다.
//
// 빈 모듈을 미리 include 해 두지 않는다. 빈 폴더는 「누가 시작해 놨나」로 읽혀서,
// 다음 사람이 매번 열어 보고 비었다는 것을 확인해야 한다. 없으면 그 질문이 안 생긴다.
// 무엇을 언제 만들 것인가의 지도는 docs/00_CODE_WALKTHROUGH.md §2 에 있다.

include("hindsight-model")     // 기록 자료 구조 (Java 17 · 의존성 없음)
include("hindsight-core")      // 기록 읽기·쓰기·가명화
include("hindsight-testkit")   // 기록 표본과 테스트 도우미

// 아직 없는 모듈 — 첫 파일을 쓸 때 폴더와 함께 여기 추가한다.
//
//   v0  demo-app                   관측 대상. 🔴 진짜 스프링 서비스로 만든다
//   v0  hindsight-recorder-simple  Filter + DataSource 감싸기 (바이트코드 없음)
//   v0  hindsight-replay           재생 · 오라클 · 테스트 생성
//   v0  hindsight-guard            🔴 패치 경로 검사. 파일을 쓰는 유일한 통로
//   v0  hindsight-brain            진단 · 채점 고리 · PR
//   v0  hindsight-cli              hs 명령어
//   v1  hindsight-agent-boot       🔴 부트스트랩 껍데기 (Java 17 · 의존성 없음)
//   v1  hindsight-agent            premain · ByteBuddy(셰이딩) · 링 버퍼
//   v3  hindsight-mcp              코딩 에이전트용 통로
//   v3  hindsight-server           저장소 · REST · 화면
