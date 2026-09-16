package io.hindsight.core.store;

import io.hindsight.model.AppInfo;
import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.Trigger;
import io.hindsight.testkit.Recordings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 🔴 <b>본문이 «글자 그대로» 왕복하는지 본다 — 파일까지 갔다 와서.</b>
 *
 * <h2>왜 기존 왕복 시험으로 부족한가</h2>
 * 옆 파일({@link RecordingCodecTest})은 {@code toJson → fromJson} 을 메모리에서 돈다.
 * 그런데 실제 경로는 <b>파일</b>이고, 파일로 나갈 때 UTF-8 로 «부호화»하는 단계가 하나 더 있다.
 * 그 단계는 메모리 왕복에서는 절대 안 드러난다.
 *
 * <p>그리고 본문은 이 도구가 다루는 값 중 <b>유일하게 남이 만든 값</b>이다. 질의문도
 * 헤더 이름도 우리가 아는 모양이 있지만, HTTP 본문은 업로드된 PNG 일 수도 gzip 일 수도 있다.
 *
 * <h2>🔴 이게 왜 「모름을 없음으로 접지 않는다」와 같은 문제인가</h2>
 * 재생 판정은 <b>본문이 기록과 글자까지 같은가</b>로 「같았다」를 말한다.
 * 부호화가 본문을 한 글자라도 바꿔 놓으면, 그 뒤의 비교는 <b>바뀐 것끼리</b> 비교하는 것이다.
 * 기록 쪽도 바뀌고 재생 쪽도 같이 바뀌면 <b>둘이 같아져서 통과한다</b> —
 * 「검사했다」가 「검사하지 않았다」와 구별이 안 되는 자리다.
 *
 * <h2>⚠️ 까다로운 글자는 소스에 «적지» 않고 코드로 만든다</h2>
 * javac 는 소스를 낱말로 쪼개기 <b>전</b>에 유니코드 이스케이프를 진짜 글자로 바꾼다.
 * 그래서 이스케이프로 적어 두면 <b>소스 파일 안에 날 제어문자가 들어앉고</b>, 이 시험이
 * 무엇을 재는지가 편집기와 파일 인코딩에 따라 달라진다. 코드로 만들면 그 흔들림이 없다.
 */
@DisplayName("본문이 파일까지 갔다 와도 글자 그대로인가")
class RecordingCodecBodyFidelityTest {

    private final RecordingCodec codec = new RecordingCodec();

    @Nested
    @DisplayName("✅ 그대로 살아 돌아오는 것")
    class 살아남는다 {

        @Test
        @DisplayName("제어 문자 — NUL · 백스페이스 · 폼피드 · 줄바꿈 · 탭 · ESC")
        void 제어문자(@TempDir Path dir) {
            // JSON 은 U+0020 미만을 날것으로 못 쓴다. 여섯 글자 이스케이프나
            // 짧은 이스케이프로 바뀌어 나갔다가 그대로 돌아와야 한다.
            String 제어문자들 = new String(new char[]{0, 8, 12, 10, 13, 9, 27, 31});

            왕복해도_같다(제어문자들 + "끝", dir);
        }

        @Test
        @DisplayName("JSON 을 깨뜨릴 수 있는 글자 — 따옴표 · 역슬래시 · 슬래시")
        void 구문문자(@TempDir Path dir) {
            왕복해도_같다("{\"key\": \"va\\lue\"} </script> 끝", dir);
        }

        @Test
        @DisplayName("BMP 밖의 글자 — 이모지처럼 char 두 개로 이뤄진 것")
        void 대리쌍(@TempDir Path dir) {
            // 🔴 여기서 놓치면 이모지가 든 본문마다 재생이 「달라졌다」로 나온다.
            String 대리쌍들 = 코드포인트(0x1F600)          // 웃는 얼굴
                    + " " + 코드포인트(0x1D11E)           // 높은음자리표
                    + " " + 코드포인트(0x1F1F0) + 코드포인트(0x1F1F7)  // 지역 표시 두 개 = 국기
                    + " 끝";

            assertThat(대리쌍들.codePoints().anyMatch(c -> c > 0xFFFF))
                    .as("시험이 재려는 그 값이 정말 들어 있는지부터 확인한다")
                    .isTrue();

            왕복해도_같다(대리쌍들, dir);
        }

        @Test
        @DisplayName("한글과 줄바꿈 — CR LF 가 LF 로 접히지 않는다")
        void 한글과_줄바꿈(@TempDir Path dir) {
            // 지금 쓰는 Files.writeString 은 줄바꿈을 안 건드린다. 그래도 재 둔다 —
            // 파일로 쓰는 방법을 바꾸는 순간 조용히 깨지는 자리라서.
            왕복해도_같다("첫 줄\n둘째 줄\r\n셋째 줄\n", dir);
        }

        @Test
        @DisplayName("🔴 빈 본문 — 「빈 문자열」과 「본문 없음」은 왕복 뒤에도 다르다")
        void 빈것과_없는것(@TempDir Path dir) {
            assertThat(파일까지_왕복(본문이(""), dir.resolve("a.json")).body())
                    .as("본문이 비어 있었다는 관찰")
                    .isEqualTo("");
            assertThat(파일까지_왕복(본문이(null), dir.resolve("b.json")).body())
                    .as("본문을 «못 봤다»는 관찰. 빈 문자열로 접히면 둘을 구별할 길이 사라진다")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("🔴 살아남지 «못하는» 것 — 그리고 그게 조용하지 않은가")
    class 못_살아남는다 {

        @Test
        @DisplayName("🔴 짝 없는 대리 문자가 든 본문은 파일로 «안 써진다» — 조용히 바뀌는 대신 터진다")
        void 짝없는_대리문자(@TempDir Path dir) {
            // 어떻게 이런 본문이 생기나: 기록기는 본문을 바이트가 아니라 String 으로 잡는다
            // (CachingRequest 가 문자셋으로 디코딩한다). 그 문자셋이 틀렸거나 본문이
            // 바이너리면 이런 반쪽짜리 char 가 나온다. UTF-8 로는 «부호화할 수 없는» 값이다.
            String 짝없는것 = ((char) 0xD800) + "짝이없다";
            assertThat(Character.isHighSurrogate(짝없는것.charAt(0)))
                    .as("시험이 재려는 그 값이 맞는지부터 확인한다")
                    .isTrue();

            Throwable 터진것 = catchThrowable(() -> codec.write(본문이(짝없는것), dir.resolve("broken.json")));

            System.out.println("== 짝 없는 대리 문자를 파일로 쓰면 ==");
            System.out.println("  " + (터진것 == null
                    ? "아무 일도 안 났다 (🔴 그러면 조용히 바뀐 것이다)"
                    : 터진것.getClass().getSimpleName() + ": " + 터진것.getMessage()));

            assertThat(터진것)
                    .as("🔴 물음표나 U+FFFD 로 바꿔서 쓰면, 재생이 그 «바뀐 것»끼리 비교하고 「같았다」가 된다")
                    .isNotNull();

            // 🔴 터지는 것만으로는 부족하다. JDK 가 주는 것은
            //    「UnmappableCharacterException: Input length = 1」 뿐이고, 그걸 그대로 흘리면
            //    운영자는 «디스크 문제»로 읽는다. 무엇이 문제인지 말해야 고칠 수 있다.
            assertThat(터진것.getMessage())
                    .as("메시지만 보고도 「본문에 UTF-8 로 못 적는 글자가 있다」를 알 수 있어야 한다")
                    .contains("UTF-8 로 적을 수 없는 글자")
                    .contains("U+D800")
                    .contains("디스크 문제가 아니다");
        }

        @Test
        @DisplayName("🔴 바이너리는 «잡는 자리»에서 이미 깨진다 — 왕복이 멀쩡한 게 온전하다는 뜻이 아니다")
        void 바이너리는_잡는_자리에서_이미_깨진다(@TempDir Path dir) {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

            // 기록기가 하는 것과 똑같이 한다 — 바이트를 문자셋으로 디코딩한다.
            String 잡힌것 = new String(png, StandardCharsets.UTF_8);
            byte[] 되돌린것 = 잡힌것.getBytes(StandardCharsets.UTF_8);

            System.out.println("── PNG 머리 8바이트를 UTF-8 로 잡으면 ──");
            System.out.println("  문자 " + 잡힌것.length() + "개 · 다시 바이트로 " + 되돌린것.length + "개"
                    + " (원래 " + png.length + "개)");

            assertThat(되돌린것)
                    .as("🔴 0x89 는 UTF-8 에서 홀로 설 수 있는 바이트가 아니다 — 대체 문자로 바뀐다")
                    .isNotEqualTo(png);

            // 🔴 그런데 «코덱»은 이 손실된 문자열을 멀쩡히 왕복시킨다.
            //    즉 왕복 시험만으로는 이 손실을 절대 못 잡는다. 그래서 여기 적어 둔다 —
            //    본문을 바이트로 잡게 되는 날, 이 시험이 바뀌어야 할 자리다.
            assertThat(파일까지_왕복(본문이(잡힌것), dir.resolve("png.json")).body())
                    .as("코덱은 «이미 깨진» 것을 그대로 보존한다. 보존됐다고 온전한 게 아니다")
                    .isEqualTo(잡힌것);
        }
    }

    @Nested
    @DisplayName("잘렸다는 사실은 본문과 «따로» 살아남는다")
    class 잘림 {

        @Test
        @DisplayName("🔴 자르기 전 크기가 본문과 같이 남는다")
        void 자른_사실이_남는다(@TempDir Path dir) {
            String 자른본문 = "가".repeat(100);
            Recording 기록 = 기록으로(new Event.HttpIn(
                    1L, "r-1", Recordings.T0, "t-1", 10L,
                    "POST", "/api/upload", Map.of(), "UploadController#put",
                    Map.of(), 자른본문, true, 8_000_000,
                    200, null, false));

            Event.HttpIn 읽은것 = 파일까지_왕복(기록, dir.resolve("t.json"));

            assertThat(읽은것.bodyTruncated()).isTrue();
            assertThat(읽은것.bodyBytes())
                    .as("🔴 자르기 «전» 크기가 없으면, 이 본문이 전부인지 아닌지 영영 모른다")
                    .isEqualTo(8_000_000);
            assertThat(읽은것.body()).hasSize(100);
        }
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private void 왕복해도_같다(String 본문, Path dir) {
        Event.HttpIn 읽은것 = 파일까지_왕복(본문이(본문), dir.resolve("r.json"));
        assertThat(읽은것.body()).as("요청 본문").isEqualTo(본문);
        assertThat(읽은것.responseBody()).as("응답 본문").isEqualTo(본문);
    }

    /** 🔴 «파일»까지 갔다 온다. 메모리 왕복은 UTF-8 부호화 단계를 통째로 건너뛴다. */
    private Event.HttpIn 파일까지_왕복(Recording 기록, Path 파일) {
        codec.write(기록, 파일);
        return (Event.HttpIn) codec.read(파일).events().getFirst();
    }

    private static String 코드포인트(int cp) {
        return new String(Character.toChars(cp));
    }

    private static Recording 본문이(String 본문) {
        return 기록으로(new Event.HttpIn(
                1L, "r-1", Recordings.T0, "t-1", 10L,
                "POST", "/api/echo", Map.of(), "EchoController#post",
                Map.of("content-type", "application/json"),
                본문, false, 본문 == null ? null : 본문.length(),
                200, 본문, false));
    }

    private static Recording 기록으로(Event 하나) {
        return new Recording(
                Recording.CURRENT_SCHEMA_VERSION,
                "body-fidelity",
                Recordings.T0,
                new Trigger(Trigger.Kind.LATENCY, Recordings.T0, "POST /api/echo", null, 10L,
                        Trigger.dedupKeyOf("POST /api/echo", Trigger.Kind.LATENCY, null), 1),
                new AppInfo("demo-app", null, null, "21.0.4", "0.1.0", null),
                List.of(하나),
                null,
                null,
                new ReplayInfo(ReplayInfo.Grade.PARTIAL, null, null, null, null, null, null),
                new Integrity(60, 0.1, 0L, 0L, 0L, 1_000L, 0L, false));
    }
}
