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
        testLogging {
            events("failed", "skipped")
            showStackTraces = true
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
