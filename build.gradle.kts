// 모든 모듈에 공통으로 적용되는 설정.
// 모듈별 의존성은 각 모듈의 build.gradle.kts 에 있다.

plugins {
    java
}

// 🔴 남의 JVM 안에 들어가는 모듈만 Java 17. 호환 범위를 넓게 둔다.
//    나머지는 21. 설계의 모듈 표(§3) 참조. 25 에서 21 로 내린 이유는
//    docs/rules/stack-decision.md 에 있다.
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

// ────────────────────────────────────────────────────────────────────────────
// 🔴 패키지끼리 누가 누구를 봐도 되는지를 빌드가 지킨다
//
// 모듈을 13개에서 6개로 접을 때, 잃는 것은 「의존 방향 강제」뿐이고 그건 «모듈 다섯 개보다
// 검사 한 개가 싸다»고 적어 뒀다. 이게 그 검사다 — 그때부터 「나중에 붙인다」로 남아 있었고,
// 그동안은 사람이 지켰다. 즉 언젠가 안 지켜질 수 있었다.
//
// 🔴 그리고 이 프로젝트는 «막는 척»을 이미 한 번 했다. 패치 경로 검사를 자기 모듈로 빼면
//    우회하기 어렵다고 믿었는데, 모듈 경계는 파일 쓰기를 못 막는다 — 다른 코드가
//    java.nio 를 직접 부르면 그만이다. 그 구멍을 여기서 막는다.
//
// ⚠️ 주석은 «걷어내고» 본다. PatchGuard 의 javadoc 에 java.nio.file.Path 를 «안 쓰는 이유»가
//    적혀 있는데, 그걸 세면 「파일을 건드린다」로 잘못 잡힌다. 설명을 적었다는 이유로
//    검사에 걸리면, 다음 사람은 설명을 지우게 된다.
// ────────────────────────────────────────────────────────────────────────────
val 패키지가_봐도_되는_것 = mapOf(
    "store" to setOf("privacy"),
    "privacy" to emptySet(),
    "replay" to emptySet(),
    "guard" to emptySet(),
    // 🔴 brain → privacy 는 2026-09-16 에 «일부러» 열었다. PR 본문은 이 도구가 만드는 글 중
    //    유일하게 저장소 «밖»으로 나가고, 올라간 뒤에는 못 되돌린다(지워도 알림 메일과 색인에 남는다).
    //    그 글은 기록에서 «지어낸» 것이라 가명화가 훑은 자리 밖에서 값이 샐 수 있어서,
    //    나가기 직전에 privacy.LeakScan 으로 한 번 더 훑는다.
    //    ⚠️ 방향은 뒤집히지 않았다 — privacy 는 여전히 아무도 안 본다.
    "brain" to setOf("guard", "replay", "privacy"),
    // cli 는 조립하는 자리라 전부 볼 수 있다. 여기까지 열어 두는 대신
    // «아래쪽»이 위를 못 보게 하는 것으로 방향을 지킨다.
    "cli" to setOf("store", "privacy", "replay", "guard", "brain"),
)

/** 🔴 파일을 직접 건드리면 안 되는 패키지. 패치 쓰기는 guard 하나를 통로로 한다. */
val 파일을_못_건드리는_패키지 = setOf("brain")

fun 주석을_걷어낸다(source: String): String =
    source
        .replace(Regex("""/\*(?:.|\n)*?\*/"""), " ")   // 블록 주석
        .replace(Regex("""//[^\n]*"""), " ")            // 줄 주석

tasks.register("checkPackageDirection") {
    group = "verification"
    description = "core 안의 패키지가 봐도 되는 것만 보는지, 파일을 건드리면 안 되는 곳이 안 건드리는지 본다"

    val coreMain = file("hindsight-core/src/main/java/io/hindsight/core")
    doLast {
        if (!coreMain.isDirectory) {
            return@doLast
        }
        val 어긴것 = mutableListOf<String>()

        coreMain.listFiles()?.filter { it.isDirectory }?.forEach { pkgDir ->
            val pkg = pkgDir.name
            val 허용 = 패키지가_봐도_되는_것[pkg] ?: return@forEach

            pkgDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { javaFile ->
                val 코드 = 주석을_걷어낸다(javaFile.readText())
                val 어디 = javaFile.relativeTo(coreMain).invariantSeparatorsPath

                Regex("""io\.hindsight\.core\.(\w+)""").findAll(코드)
                    .map { it.groupValues[1] }
                    .filter { it != pkg && 패키지가_봐도_되는_것.containsKey(it) && it !in 허용 }
                    .distinct()
                    .forEach { 본것 ->
                        어긴것 += "  $어디  →  core.$본것   (${pkg} 이(가) 봐도 되는 것: ${허용.ifEmpty { setOf("없음") }.joinToString()})"
                    }

                if (pkg in 파일을_못_건드리는_패키지
                    && Regex("""java\.nio\.file|java\.io\.File\b""").containsMatchIn(코드)
                ) {
                    어긴것 += "  $어디  →  🔴 파일을 «직접» 건드린다. 패치 쓰기는 core.guard 를 통로로 한다"
                }
            }
        }

        if (어긴것.isNotEmpty()) {
            throw GradleException(
                """
                |패키지 의존 방향을 어겼다 (${어긴것.size}곳):
                |
                |${어긴것.joinToString("\n")}
                |
                |🔴 아래쪽 패키지가 위쪽을 보기 시작하면 「재생만 떼어내서 시험한다」가 불가능해지고,
                |   그때는 이미 되돌리기에 늦다. 방향을 되돌리거나, 정말 바꿔야 한다면
                |   루트 build.gradle.kts 의 「패키지가_봐도_되는_것」을 고치고
                |   그 판단을 docs/rules/module-boundary-decision.md 에 남긴다.
                """.trimMargin()
            )
        }
    }
}

// 🔴 check 에 건다. 「돌리는 것을 기억해야 하는 검사」는 언젠가 안 돌린다.
project(":hindsight-core").afterEvaluate {
    tasks.named("check") { dependsOn(rootProject.tasks.named("checkPackageDirection")) }
}

// ────────────────────────────────────────────────────────────────────────────
// 🔴 실행 스크립트가 «돌 수 있는 모양»인지 빌드가 본다
//
// 2026-09-16 에 `bin/hs.bat` 을 만들고 바로 돌렸더니 이렇게 죽었다:
//   'HIS' is not recognized as an internal or external command
// 'HIS' 는 주석 한 줄의 «조각»이다. 원인 둘이 겹쳐 있었다.
//   ① 줄바꿈이 LF 뿐이라 cmd 가 줄을 엉뚱한 자리에서 잘랐다
//   ② 주석의 한글이 UTF-8 인데 cmd 는 콘솔 코드페이지로 읽어서 깨진 바이트를
//      «명령»으로 실행하려 했다
//
// .gitattributes 에 eol=crlf 가 이미 있었지만, 그건 «git 을 거칠 때» 고쳐 준다.
// 방금 만든 파일은 아직 안 거쳤고, 그래서 안 잡혔다. 작업 트리에서 바로 잡는다.
//
// ⚠️ bin/hs(유닉스 쪽)는 반대다. CRLF 가 붙으면 `#!/usr/bin/env bash` 뒤의 \r 때문에
//    「해석기를 못 찾겠다」가 난다. 그래서 둘을 «다른 규칙»으로 본다.
// ────────────────────────────────────────────────────────────────────────────
tasks.register("checkLauncherScripts") {
    group = "verification"
    description = "bin/ 의 실행 스크립트가 각 운영체제에서 실제로 돌 수 있는 모양인지 본다"

    val batFiles = fileTree("bin") { include("*.bat", "*.cmd") }
    val shFiles = fileTree("bin") { include("hs", "*.sh") }
    inputs.files(batFiles, shFiles)

    doLast {
        val 어긴것 = mutableListOf<String>()

        batFiles.forEach { f ->
            val bytes = f.readBytes()
            val lf = bytes.count { it == '\n'.code.toByte() }
            val crlf = (0 until bytes.size - 1).count {
                bytes[it] == '\r'.code.toByte() && bytes[it + 1] == '\n'.code.toByte()
            }
            if (lf != crlf) {
                어긴것 += "  bin/${f.name}  →  줄바꿈이 CRLF 가 아니다 (LF ${lf - crlf}줄). " +
                        "cmd 가 줄을 잘못 끊어 주석 조각을 «명령»으로 실행한다"
            }
            val 비아스키 = bytes.count { it < 0 }
            if (비아스키 > 0) {
                어긴것 += "  bin/${f.name}  →  ASCII 가 아닌 바이트 ${비아스키}개. " +
                        "cmd 는 콘솔 코드페이지로 읽으므로 한글·이모지가 깨져 «명령»이 된다. " +
                        "하고 싶은 말은 자바 쪽에 적는다 — 거기는 UTF-8 이 끝까지 간다"
            }
        }

        shFiles.forEach { f ->
            val bytes = f.readBytes()
            val crlf = (0 until bytes.size - 1).count {
                bytes[it] == '\r'.code.toByte() && bytes[it + 1] == '\n'.code.toByte()
            }
            if (crlf > 0) {
                어긴것 += "  bin/${f.name}  →  CRLF 가 ${crlf}줄 있다. " +
                        "셔뱅 뒤의 \r 때문에 「해석기를 못 찾겠다」로 죽는다"
            }
        }

        if (어긴것.isNotEmpty()) {
            throw GradleException(
                """
                |실행 스크립트가 돌 수 없는 모양이다 (${어긴것.size}곳):
                |
                |${어긴것.joinToString("\n")}
                |
                |🔴 이건 「돌려 보면 안다」가 아니다. 돌려 본 사람만 알고, 그 사람은
                |   보통 이 도구를 처음 쓰는 사람이다.
                """.trimMargin()
            )
        }
    }
}

tasks.named("check") { dependsOn("checkLauncherScripts") }
