// v0 기록기. 🔴 관측 대상 앱 «안»에서 돈다 — demo-app 이 이 모듈을 의존한다.
//
// 왜 demo-app 안의 패키지가 아니라 따로 모듈인가:
// 모듈을 나누는 기준은 「누구의 클래스패스에 올라가나」 하나뿐인데(docs/rules/module-boundary-decision.md),
// 이 코드는 «안»으로 들어가고 hindsight-core 는 «밖»에 있다. 그래서 core 안에 둘 수 없다.
// demo-app 안에 두지 않은 이유는 v1 에 있다 — 진짜 에이전트가 만든 기록과 이 기록이
// 같은지 대조하는 것이 회귀 검사인데(docs/DESIGN.md §2-1 의 「기록기가 왜 두 벌인가」),
// 기록기가 포트폴리오 앱의 소스 트리 안에 있으면 그 대조가 demo-app 을 끌고 다닌다.
//
// 🔴 한때 「core 를 의존하면 Spring AI 와 picocli 가 demo-app 으로 딸려 간다」를 이유로
//    이 판단을 미뤄 뒀다. 2026-09-15 실측으로 갈랐다:
//    demo-app 의 runtimeClasspath 에 이미 Jackson 2.18.2 가 있고 core 가 쓰는 것과
//    «같은 버전»이라, 지금 이 의존으로 늘어나는 외부 jar 는 0 개다.
//    딸려 들어온다던 것은 오늘의 core 가 아니라 «미래의» core 다.
//    그 미래가 오는 순간을 아래 checkRecorderDependencies 가 잡는다.

dependencies {
    // api 인 이유: 이 모듈의 공개 메서드 서명에 model 의 타입이 그대로 나온다.
    api(project(":hindsight-model"))

    // 🔴 기록을 파일로 쓰는 유일한 통로. 가명화가 이 안에 묶여 있다.
    //    (io.hindsight.core.store.RecordingStore — 쓰기와 가명화가 한 함수 안이다)
    api(project(":hindsight-core"))

    // 관측 대상이 서블릿 앱이라 Filter 를 만들려면 서블릿 API 가 필요하다.
    // compileOnly 인 이유: 🔴 이건 «호스트 앱이 이미 들고 있는» 것이다. 우리가 런타임에
    // 끌고 들어가면 앱의 것과 두 벌이 되어 ClassCastException 이 난다.
    compileOnly(libs.servlet.api)

    testImplementation(libs.servlet.api)
    testImplementation(libs.h2)
    testImplementation(testFixtures(project(":hindsight-model")))
}

// ────────────────────────────────────────────────────────────────────────────
// 🔴 이 모듈이 관측 대상 앱으로 끌고 들어가는 것을 빌드가 센다
//
// 이 모듈은 demo-app 의 클래스패스에 올라간다. 여기 들어온 의존성은 그대로 남의 앱에
// 섞인다. 지금은 Jackson 뿐이고 demo-app 이 이미 같은 버전을 쓰고 있어서 늘어나는 게 없다.
//
// 🔴 그런데 hindsight-core 는 앞으로 Spring AI 와 picocli 를 갖게 된다(진단·명령줄).
//    그 순간 이 모듈을 통해 그것들이 demo-app 으로 들어간다. 그때가 core.store 를
//    따로 떼어낼 시점이고, 이 검사가 그 시점을 «말해 준다».
//
// 규율을 문단으로 적으면 언젠가 안 지켜진다. 그래서 검사로 만든다.
// (같은 생각으로 만든 것: 루트 build.gradle.kts 의 checkAgentDependencies · checkDocLinks)
// ────────────────────────────────────────────────────────────────────────────
val allowedRecorderDeps = setOf("hindsight-model", "hindsight-core", "jackson-")

tasks.register("checkRecorderDependencies") {
    group = "verification"
    description = "v0 기록기가 관측 대상 앱으로 끌고 들어가는 의존성이 늘었는지 본다"

    val runtimeFiles = configurations.findByName("runtimeClasspath")
    doLast {
        val offenders = runtimeFiles
            ?.resolvedConfiguration
            ?.resolvedArtifacts
            ?.map { it.name }
            ?.filterNot { name -> allowedRecorderDeps.any { name.startsWith(it) } }
            ?: emptyList()

        if (offenders.isNotEmpty()) {
            throw GradleException(
                """
                |v0 기록기가 관측 대상 앱으로 새 의존성을 끌고 들어간다: $offenders
                |
                |이 모듈은 demo-app 과 «같은» 클래스패스에서 돈다. 여기 들어간 라이브러리는
                |그대로 그 앱에 섞이고, 앱이 다른 버전을 쓰고 있으면 우리가 아니라 그 앱이 죽는다.
                |
                |hindsight-core 가 커져서 이렇게 됐다면, 이제 core.store(읽기·쓰기)를
                |따로 떼어낼 시점이다. 그 판단을 docs/rules/module-boundary-decision.md 에 남긴다.
                |단순히 위 allowedRecorderDeps 에 이름을 더하는 것으로 넘기지 말 것 —
                |그건 검사를 끄는 것이지 통과하는 게 아니다.
                """.trimMargin()
            )
        }
    }
}

tasks.named("check") { dependsOn("checkRecorderDependencies") }
