// 관측 대상. Hindsight 가 지켜볼 앱이다.
//
// 🔴 지금은 「배관이 되는지」를 재기 위한 최소 상태다. 설계(§3-4)가 정한 대로
// JPA 연관관계·트랜잭션·Redis·Docker Compose 를 갖춘 진짜 서비스로 키운다.
// 이유: 평가할 수 없는 포트폴리오는 높은 점수가 아니라 0점을 받는다.
//
// DB 는 H2 다 — 파일도 서버도 없이 메모리에서 돈다. 그래도 커넥션 풀은
// HikariCP 를 그대로 쓰므로, 「풀 프록시 때문에 SQL 이 두 번 세이나」는
// 이 상태에서도 그대로 확인된다.

plugins {
    alias(libs.plugins.springboot)
    alias(libs.plugins.spring.dependency.management)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")

    // 🔴 v0 기록기. 이 앱 «안»에서 돈다.
    //
    // 2026-09-15 측정: 이 한 줄로 늘어나는 외부 jar 는 «0개»다. 기록기가 딸고 오는 것은
    // Jackson 2.18.2 뿐인데 spring-boot-starter-web 이 이미 같은 버전을 들고 있다.
    // 「Spring AI 와 picocli 가 딸려 온다」던 걱정은 오늘의 core 가 아니라 미래의 core 다.
    // 그 미래가 오는 순간은 :hindsight-recorder-simple:checkRecorderDependencies 가 잡는다.
    implementation(project(":hindsight-recorder-simple"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // 🔴 «생성된» 테스트를 이 JVM 안에서 직접 돌리려면 JUnit 을 프로그램으로 부를 수 있어야 한다.
    //    보통은 Gradle 이 대신 불러 주므로 testRuntimeOnly 로 충분한데, 여기서는
    //    우리 코드가 Launcher 를 «직접» 쓰기 때문에 컴파일 시점에도 필요하다.
    testImplementation(libs.junit.platform.launcher)
}

// ────────────────────────────────────────────────────────────────────────────
// 🔴 기록 하나를 명령줄에서 «진짜로» 재생한다
//
//   ./gradlew :demo-app:replay -Pid=a1b2c3d4
//
// 왜 hs 가 아니라 여기인가: 재생은 DB 를 되돌리고 요청을 다시 보내는 일이고,
// 둘 다 «앱만» 할 수 있다. 명령줄에는 그 앱이 없다 — 그게 이유의 전부다.
//
// ⚠️ 시험 소스의 클래스를 돌린다. 운영 소스에 두면 관측 대상 앱이 재생 코드를
//    배포에 싣고 다니게 되고, 그건 「기록기만 앱 안에 들어간다」는 경계를 무너뜨린다.
// ────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("replay") {
    group = "application"
    description = "기록 하나를 재생한다 (-Pid=<번호>)"

    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.hindsight.demo.hindsight.ReplayRunner")

    // 🔴 콘솔 인코딩을 명시한다. 자바 18 부터 표준 출력은 stdout.encoding 이 정하고,
    //    안 주면 콘솔 코드페이지를 따라가서 한글이 ?? 로 나갈 수 있다.
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

    doFirst {
        val id = project.findProperty("id")?.toString()
        if (id.isNullOrBlank()) {
            throw GradleException(
                """
                |기록 번호가 필요하다.
                |
                |  ./gradlew :demo-app:replay -Pid=<번호>
                |
                |번호는 `hs list` 로 본다. 기록 폴더는 HINDSIGHT_STORE_DIR 이 정한다.
                """.trimMargin()
            )
        }
        args(id)
        project.findProperty("store")?.toString()?.let {
            systemProperty("HINDSIGHT_STORE_DIR", it)
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// 🔴 고리를 «진짜로» 한 바퀴 돌린다 — 진짜 LLM · 진짜 파일 · 진짜 빌드
//
//   ./gradlew :demo-app:fix -Pid=a1b2c3d4
//
// ⚠️ 이 명령은 «작업 트리를 진짜로 고친다». 끝나면 되돌리고, 되돌리기 전에
//    고친 내용을 build/hindsight-patch/ 에 남긴다 — 「되돌렸다」와 「버렸다」는 다르다.
// ────────────────────────────────────────────────────────────────────────────
tasks.register<JavaExec>("fix") {
    group = "application"
    description = "기록 하나를 LLM 에게 고치게 하고 네 겹으로 채점한다 (-Pid=<번호>)"

    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.hindsight.demo.hindsight.FixRunner")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")

    doFirst {
        val id = project.findProperty("id")?.toString()
        if (id.isNullOrBlank()) {
            throw GradleException("기록 번호가 필요하다.  ./gradlew :demo-app:fix -Pid=<번호>")
        }
        args(id)
        project.findProperty("store")?.toString()?.let { systemProperty("HINDSIGHT_STORE_DIR", it) }
    }
}

// 🔴 ReplayPassesTest 는 «지금 코드에 증상이 남아 있으면 실패»하는 시험이다.
//    저장소의 기본 상태는 「버그가 있는」 상태이므로, 전체 빌드에서는 «빼 둔다» —
//    안 빼면 빌드가 늘 빨간불이고, 그러면 아무도 빨간불을 안 본다.
tasks.named<Test>("test") {
    filter { excludeTestsMatching("io.hindsight.demo.hindsight.ReplayPassesTest") }
}

// ⚠️ 그런데 «빼 두면 --tests 로 고를 수도 없다».
//    2026-09-16 에 고리가 그걸로 세 번 헛돌았다 —
//    `No tests found for given includes: [*ReplayPassesTest*]` 가 「테스트 실패」로 읽혔다.
// 🔴 그래서 «전용 작업»을 따로 둔다. 고리는 이걸 부른다.
tasks.register<Test>("replayCheck") {
    group = "verification"
    description = "지금 코드에서 재생 테스트가 통과하는지 본다 (고치는 고리가 부른다)"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("io.hindsight.demo.hindsight.ReplayPassesTest") }

    // 🔴 결과를 캐시하지 않는다. 같은 소스라도 «패치가 붙었나»에 따라 답이 달라져야 한다.
    outputs.upToDateWhen { false }
}
