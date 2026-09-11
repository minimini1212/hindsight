// 모든 모듈에 공통으로 적용되는 설정.
// 모듈별 의존성은 각 모듈의 build.gradle.kts 에 있다.

plugins {
    java
}

// 🔴 남의 JVM 안에 들어가는 모듈만 Java 17. 호환 범위를 넓게 둔다.
//    나머지는 25. 설계의 모듈 표(§3) 참조.
val agentModules = setOf("hindsight-model", "hindsight-agent", "hindsight-agent-boot")

allprojects {
    group = "io.hindsight"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    // java 가 아니라 java-library 인 이유: 모듈 대부분이 hindsight-model 의 타입을
    // 자기 메서드 서명에 그대로 노출한다. 그런 의존성은 api 로 선언해야 쓰는 쪽까지
    // 타입이 보인다. implementation 으로 감추면 호출하는 쪽이 컴파일이 안 된다.
    apply(plugin = "java-library")

    val libs = rootProject.extensions
        .getByType<VersionCatalogsExtension>()
        .named("libs")

    // 🔴 바깥 모듈은 21, 남의 JVM 에 들어가는 모듈은 17.
    //    25 를 안 쓰는 이유: 이 프로젝트에 아무것도 더 주지 않으면서 JDK 를 새로 받게 만든다.
    //    레코드·패턴 매칭·가상 스레드는 전부 21 에 있다. 근거: docs/rules/stack-decision.md
    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(if (project.name in agentModules) 17 else 21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        // 🔴 이게 없으면 한글이 깨진다. 윈도우에서 Gradle 이 테스트 JVM 을
        // x-windows-949(옛 한글 인코딩)로 띄우기 때문이다. 이 프로젝트는 테스트 이름도
        // 출력도 전부 한글이라, 없으면 실패 원인을 읽을 수가 없다.
        // 2026-09-11 실제로 겪었다 — 실측 결과가 「?? ?? ??」로 나왔다.
        systemProperty("file.encoding", "UTF-8")
        jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

        testLogging {
            events("failed", "skipped")
            showStackTraces = true
            showStandardStreams = false // 필요할 때만 -i 로 본다
        }
    }

    dependencies {
        add("testImplementation", platform(libs.findLibrary("junit-bom").get()))
        add("testImplementation", libs.findLibrary("junit-jupiter").get())
        add("testImplementation", libs.findLibrary("assertj").get())
        add("testRuntimeOnly", libs.findLibrary("junit-platform-launcher").get())
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 🔴 문서의 링크가 실재하는지 빌드가 직접 본다
//
// 이 프로젝트는 문서가 서로를 많이 가리킨다 — 설계가 결정 기록을, 결정 기록이 실측을,
// 실측이 트러블슈팅을. 그런데 파일을 옮기면 그 링크들이 «조용히» 끊어진다.
// 끊어진 것을 알아채는 시점은 누군가 그 링크를 눌러 보는 순간이고, 그때는 이미 한참 뒤다.
//
// 설계 리뷰가 이 프로젝트에서 지적한 것 중 하나가 정확히 이거였다 —
// 「문서가 존재하지 않는 파일 셋을 가리키고 있다」.
//
// 2026-09-11 문서를 날짜 폴더로 옮기면서 상대 경로 다섯 개가 한 번에 어긋났다.
// 손으로 확인해서 찾았지만, 손으로 하는 확인은 언젠가 안 한다.
// ────────────────────────────────────────────────────────────────────────────
val docsRoot = projectDir

tasks.register("checkDocLinks") {
    group = "verification"
    description = "문서의 상대 링크가 실재하는 파일을 가리키는지 본다"

    doLast {
        val linkPattern = Regex("""\]\(([^)\s]+)\)""")
        val skipped = setOf("build", ".git", ".gradle", ".idea")
        val broken = mutableListOf<String>()

        docsRoot.walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile && it.extension == "md" }
            .forEach { markdown ->
                linkPattern.findAll(markdown.readText()).forEach { match ->
                    val raw = match.groupValues[1]
                    // 바깥 주소와 문서 안 앵커(#절)는 우리가 확인할 수 있는 게 아니다
                    if (raw.startsWith("http") || raw.startsWith("mailto:") || raw.startsWith("#")) {
                        return@forEach
                    }
                    val target = raw.substringBefore('#')
                    if (target.isBlank()) return@forEach

                    if (!File(markdown.parentFile, target).exists()) {
                        broken += "  ${markdown.relativeTo(docsRoot).invariantSeparatorsPath}  →  $target"
                    }
                }
            }

        if (broken.isNotEmpty()) {
            throw GradleException(
                """
                |문서가 없는 파일을 가리킨다 (${broken.size}곳):
                |
                |${broken.joinToString("\n")}
                |
                |파일을 옮겼다면 «깊이»가 바뀌었는지 보라 — docs/reports/2026-09-11/a.md 에서
                |docs/rules/ 로 가려면 ../../rules/ 이지 ../rules/ 가 아니다.
                """.trimMargin()
            )
        }
    }
}

// 🔴 check 에 건다. 「돌리는 것을 기억해야 하는 검사」는 언젠가 안 돌린다.
tasks.named("check") { dependsOn("checkDocLinks") }

// ────────────────────────────────────────────────────────────────────────────
// 🔴 에이전트 의존성 금지선을 빌드가 직접 지킨다
//
// 규율 파일에 "의존성 0" 이라고 적어두는 것만으로는 지켜지지 않는다.
// 언젠가 누군가(사람이든 Claude든) 편하다는 이유로 한 줄을 추가하고,
// 그 순간 남의 앱 클래스패스가 오염된다. 그래서 검사를 빌드에 건다.
//
// 허용: hindsight-model(우리 것), ByteBuddy(셰이딩됨), 테스트 의존성
// 그 밖의 것이 runtimeClasspath 에 들어오면 빌드를 실패시킨다.
// ────────────────────────────────────────────────────────────────────────────
val allowedAgentDeps = setOf("byte-buddy", "byte-buddy-agent", "hindsight-model")

configure(subprojects.filter { it.name in agentModules }) {
    tasks.register("checkAgentDependencies") {
        group = "verification"
        description = "에이전트에 허용되지 않은 의존성이 들어왔는지 본다"

        val runtimeFiles = configurations.findByName("runtimeClasspath")
        doLast {
            val offenders = runtimeFiles
                ?.resolvedConfiguration
                ?.resolvedArtifacts
                ?.map { it.name }
                ?.filterNot { name -> allowedAgentDeps.any { name.startsWith(it) } }
                ?: emptyList()

            if (offenders.isNotEmpty()) {
                throw GradleException(
                    """
                    |${project.name} 에 허용되지 않은 의존성이 있다: $offenders
                    |
                    |이 모듈은 관측 대상 앱과 같은 클래스패스에서 돈다.
                    |여기 들어간 라이브러리는 그대로 남의 앱에 섞이고, 버전이 어긋나면
                    |우리 도구가 아니라 그 앱이 죽는다.
                    |
                    |정말 필요하면 셰이딩(io.hindsight.shaded.* 로 옮겨 넣기)한 뒤
                    |위 allowedAgentDeps 에 추가하고, 그 판단을
                    |docs/rules/agent-safety-decision.md 에 남긴다.
                    """.trimMargin()
                )
            }
        }
    }

    tasks.named("check") { dependsOn("checkAgentDependencies") }
}
