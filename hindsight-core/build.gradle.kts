// 관측 대상 앱 «밖»에서 도는 것 전부. 그래서 의존성이 자유롭다.
//
// 안은 패키지로 나뉜다 — store(읽기·쓰기) · privacy(가명화) · replay(재생·오라클) ·
// guard(패치 경로 검사) · brain(진단·채점) · cli(명령어).
// 🔴 이것들을 모듈로 나누지 않는 이유: 나눠도 얻는 게 없다. 모듈 경계는 「누구의
// 클래스패스에 올라가나」를 가를 때만 값을 한다. 이 안의 코드는 전부 같은 자리에 올라간다.
// 🧭 근거: docs/rules/module-boundary-decision.md
//
// 🔴 매핑 설정(믹스인)이 여기 있는 이유: hindsight-model 에 Jackson 애너테이션을
// 붙이면 그 라이브러리가 남의 JVM 으로 딸려 들어간다. 그래서 model 은 애너테이션 없는
// 순수 record 로 두고, Jackson 에게 「이 클래스는 이렇게 읽어라」를 바깥에서 알려준다.

dependencies {
    api(project(":hindsight-model"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(testFixtures(project(":hindsight-model")))
}

// ────────────────────────────────────────────────────────────────────────────
// 🔴 `hs` 를 «진짜로 돌릴 수 있게» 만든다
//
// 여기까지 명령줄은 클래스로만 있었다. 그러면 시험 안에서는 도는데 사람은 못 쓴다 —
// 그리고 「사람이 못 쓰는 도구」는 사람이 쓸 때 무엇이 깨지는지도 영영 모른다.
// 실제로 윈도우 콘솔에서 한글이 깨지는지 여기까지 «한 번도 안 봤다».
//
// 의존성을 한 덩어리로 넣는다(Jackson). 받는 쪽이 클래스패스를 맞추게 하면
// 그 순간 「어떻게 돌리나」가 문서로만 남고, 문서는 낡는다.
// ────────────────────────────────────────────────────────────────────────────
tasks.register<Jar>("hsJar") {
    group = "distribution"
    description = "hs 명령어를 혼자 도는 jar 하나로 만든다"

    archiveBaseName.set("hs")
    archiveVersion.set("")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("hs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes("Main-Class" to "io.hindsight.core.cli.HindsightCli")
    }

    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    })
    // 🔴 서명 파일을 걷어낸다. 안 그러면 한 덩어리로 묶인 jar 가
    //    「서명이 안 맞는다」며 실행 시점에 죽는다.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}
