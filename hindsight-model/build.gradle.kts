// 🔴 이 모듈은 관측 대상 앱의 클래스패스에 그대로 올라간다.
//
// 의존성을 추가하지 않는다. Jackson 애너테이션도 금지다 — 붙이는 순간
// jackson-annotations 가 이 모듈의 의존성이 되고, 에이전트가 이 모듈을 쓰므로
// Jackson 이 남의 JVM 에 들어간다. 앱이 다른 버전을 쓰고 있으면 우리가 아니라
// 그 앱이 죽는다.
//
// 매핑 설정은 전부 hindsight-core 쪽 믹스인이 맡는다.
// 근거: docs/rules/agent-safety-decision.md §3
//
// 이 금지선은 루트 build.gradle.kts 의 checkAgentDependencies 가 지킨다.
// 문서에만 적어두면 언젠가 누가 한 줄 추가한다.
// 2026-09-10 실제로 막는지 확인함: jackson-annotations 를 넣으니 빌드가 실패했다.

plugins {
    // 기록 표본(io.hindsight.testkit.Recordings)을 여기 둔다.
    //
    // 🔴 표본을 위한 «모듈»을 따로 만들지 않는다. 한때 hindsight-testkit 이 있었는데,
    // 하는 일이 「자료 구조로 표본을 만든다」뿐이라 자료 구조 옆이 제자리였다.
    //
    // testFixtures 는 별도 소스 세트라 «본체 jar 에 안 들어간다». 그래서 관측 대상
    // 앱으로 딸려 가지 않는다 — 위의 의존성 0 규칙이 그대로 유지된다.
    // 쓰는 쪽: testImplementation(testFixtures(project(":hindsight-model")))
    `java-test-fixtures`
}

// 본체 의존성 없음. 이 빈 자리가 이 모듈의 요점이다.
