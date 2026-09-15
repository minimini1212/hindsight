package io.hindsight.core.cli;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;
import io.hindsight.core.store.RecordingStore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("hs — 기록을 들여다보는 명령어")
class HindsightCliTest {

    private static final Instant T0 = Instant.parse("2026-09-16T10:00:00Z");

    @TempDir
    Path storeDir;

    private final ByteArrayOutputStream 찍힌것 = new ByteArrayOutputStream();

    private HindsightCli cli() {
        return new HindsightCli(storeDir, new PrintStream(찍힌것, true, StandardCharsets.UTF_8));
    }

    private String 출력() {
        return 찍힌것.toString(StandardCharsets.UTF_8);
    }

    /** 진짜 기록 파일을 하나 만든다 — 쓰는 통로(가명화가 묶인 곳)를 그대로 쓴다. */
    private Recording 기록을_남긴다(String id, Integrity integrity) {
        SqlShapes.Shape 회원 = SqlShapes.of("select * from member where id = 1");
        Recording recording = new Recording(1, id, T0,
                new Trigger(Trigger.Kind.LATENCY, T0, "GET /api/orders", null, 3412L, "k", 1204),
                new AppInfo("demo-app", null, null, "21", "0.1.0", null),
                List.of(new Event.HttpIn(1, "r-1", T0, "t", 3412L, "GET", "/api/orders",
                        null, null, Map.of("authorization", "Bearer SECRET-DO-NOT-SHOW"),
                        null, false, 0, 200, "{\"orders\":[]}", false)),
                new Summary(60, List.of(new Summary.SqlShape(회원.hash(), 회원.normalized(), 20, 40)),
                        List.of(), null),
                null, null, integrity);

        new RecordingStore(storeDir, 1024L * 1024, "시험용-키").write(recording);
        return recording;
    }

    // ────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("기본 동작")
    class 기본 {

        @Test
        @DisplayName("list 가 기록을 보여 준다")
        void list() {
            기록을_남긴다("a1b2", new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));

            assertThat(cli().run(new String[]{"list"})).isZero();
            assertThat(출력()).contains("a1b2").contains("GET /api/orders");
        }

        @Test
        @DisplayName("show 가 기록 하나를 자세히 보여 준다")
        void show() {
            기록을_남긴다("a1b2", new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));

            assertThat(cli().run(new String[]{"show", "a1b2"})).isZero();
            assertThat(출력()).contains("무엇이 이 기록을 만들었나");
            assertThat(출력()).contains("1204번");
        }

        @Test
        @DisplayName("명령이 없으면 쓰는 법을 보여 주고 0 이 아닌 값을 돌려준다")
        void 명령이_없으면() {
            assertThat(cli().run(new String[]{})).isNotZero();
            assertThat(출력()).contains("hs list");
        }
    }

    @Nested
    @DisplayName("🔴 「없다」와 「못 읽었다」를 다르게 말한다")
    class 없음과_못읽음 {

        @Test
        @DisplayName("기록 폴더가 아예 없으면 그렇게 말한다 — 「기록이 없다」가 아니다")
        void 폴더가_없다() {
            HindsightCli cli = new HindsightCli(storeDir.resolve("없는폴더"),
                    new PrintStream(찍힌것, true, StandardCharsets.UTF_8));

            assertThat(cli.run(new String[]{"list"})).isNotZero();
            assertThat(출력()).contains("기록 폴더가 없다");
            assertThat(출력()).doesNotContain("기록이 없다.");
        }

        @Test
        @DisplayName("폴더는 있는데 기록이 없으면 「기록이 없다」")
        void 기록이_없다() {
            assertThat(cli().run(new String[]{"list"})).isZero();
            assertThat(출력()).contains("기록이 없다");
        }

        @Test
        @DisplayName("없는 번호를 물으면 있는 번호를 보는 법을 알려 준다")
        void 없는_번호() {
            기록을_남긴다("a1b2", new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));

            assertThat(cli().run(new String[]{"show", "없는번호"})).isNotZero();
            assertThat(출력()).contains("hs list");
        }

        @Test
        @DisplayName("🔴 못 읽은 파일이 있으면 «조용히 빼지 않고» 말한다")
        void 못_읽은_파일을_말한다() throws Exception {
            기록을_남긴다("a1b2", new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));
            Files.writeString(storeDir.resolve("깨진것.json"), "{ 이건 JSON 이 아니다");

            assertThat(cli().run(new String[]{"list"})).isZero();
            // 안 보이면 그 파일이 «없는 것»으로 읽힌다.
            assertThat(출력()).contains("못 읽은 기록이 있다");
            assertThat(출력()).contains("깨진것.json");
            // 그리고 나머지는 그대로 보여 준다.
            assertThat(출력()).contains("a1b2");
        }
    }

    @Nested
    @DisplayName("🔴 화면에 비밀이 새지 않는다")
    class 비밀 {

        @Test
        @DisplayName("인증 헤더는 기록에 이미 없고, 화면에도 없다")
        void 인증_헤더가_안_보인다() {
            기록을_남긴다("a1b2", new Integrity(60, 57.0, 0, 0, 0, 1000, 0, false));

            cli().run(new String[]{"show", "a1b2"});

            // 🔴 가명화가 «쓰기 직전»에 이미 했으므로 파일에도 없다. 화면은 그 파일을 읽을 뿐이다.
            assertThat(출력()).doesNotContain("SECRET-DO-NOT-SHOW");
        }
    }

    @Nested
    @DisplayName("🔬 만들어진 화면을 통째로 찍어 본다")
    class 눈으로_본다 {

        @Test
        @DisplayName("짧게 담긴 기록의 화면")
        void 짧게_담긴_기록() {
            기록을_남긴다("a1b2", new Integrity(60, 4.2, 17, 0, 0, 1000, 0, false));

            cli().run(new String[]{"list"});
            cli().run(new String[]{"show", "a1b2"});

            System.out.println();
            System.out.println("════════ hs 화면 ════════");
            System.out.println(출력());
            System.out.println("═════════════════════════");

            // 🔴 조각 단언만 있으면 «합쳐 놓으면 틀린» 화면이 나온다. 통째로 찍어 두고,
            //    눈으로 찾은 것은 여기 단언으로 못 박는다.
            assertThat(출력()).contains("4.2초만 담겼다");
            assertThat(출력()).contains("17건을 «못 받았다»");
        }
    }
}
