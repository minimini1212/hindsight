package io.hindsight.core.store;

import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.testkit.Recordings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 기록 형식이 쓰는 쪽과 읽는 쪽에서 갈라지지 않는지 본다.
 *
 * <p>🔴 이 테스트가 필수인 이유: 기록을 만드는 코드(v0 은 recorder-simple, v1 은 agent)와
 * 읽는 코드(core)가 따로 있다. 한쪽만 바뀌면 컴파일은 통과하고 테스트도 통과하는데
 * 기록만 조용히 깨진다. 깨진 걸 알아채는 시점은 <b>정작 필요한 사고가 났을 때</b>다.
 */
class RecordingCodecTest {

    private final RecordingCodec codec = new RecordingCodec();

    @Nested
    @DisplayName("왕복 — 쓴 것을 읽으면 같아야 한다")
    class RoundTrip {

        @Test
        @DisplayName("모든 자리가 채워진 기록")
        void fullRecordingSurvives() {
            Recording original = Recordings.full();

            Recording restored = codec.fromJson(codec.toJson(original));

            assertThat(restored).isEqualTo(original);
        }

        @Test
        @DisplayName("🔴 「모름」이 「없음」으로 접히지 않는다")
        void unknownsAreNotFoldedIntoEmpty() {
            Recording original = Recordings.mostlyUnknown();

            Recording restored = codec.fromJson(codec.toJson(original));

            assertThat(restored).isEqualTo(original);
            // 아래는 위 한 줄로 이미 보장되지만, 무엇이 중요한지를 이름으로 남긴다.
            // 이 테스트가 깨질 때 「왜 이게 중요한가」를 다시 알아내지 않아도 되도록.
            assertThat(restored.app().gitCommit()).as("커밋을 못 알아낸 것은 빈 문자열이 아니다").isNull();
            assertThat(restored.app().gitDirty()).as("확인 못 한 것은 false 가 아니다").isNull();
            assertThat(restored.summary()).as("요약이 없는 것과 빈 요약은 다르다").isNull();
            assertThat(restored.jfr()).as("JFR 을 못 뜬 것과 지표가 0 인 것은 다르다").isNull();
            assertThat(restored.replay().missing()).as("대조를 안 한 것과 다 잡은 것은 다르다").isNull();
            assertThat(restored.replay().baselineFailed()).as("안 돌려본 것은 false 가 아니다").isNull();
        }

        @Test
        @DisplayName("어느 요청에도 안 붙는 이벤트도 그대로 남는다")
        void eventWithoutCorrelationIdSurvives() {
            Recording restored = codec.fromJson(codec.toJson(Recordings.full()));

            assertThat(restored.events())
                    .as("스케줄러가 낸 SQL 처럼 요청에 안 붙는 이벤트. 버리면 원인을 놓친다")
                    .anyMatch(e -> e.corrId() == null);
        }

        @Test
        @DisplayName("🔴 파생 메서드가 JSON 필드로 새지 않는다")
        void derivedHelpersDoNotLeakIntoJson() {
            // model 에는 Jackson 애너테이션을 못 붙인다 — 그 라이브러리가 관측 대상 앱의
            // 클래스패스로 딸려 들어가기 때문이다. 그래서 @JsonIgnore 로 하나씩 막을 수 없고,
            // getXxx·isXxx 로 이름 지은 파생 메서드는 자동으로 JSON 필드가 되어 왕복을 깬다.
            //
            // 매퍼에서 getter 인식을 끄는 방법은 못 쓴다 — Jackson 은 record 접근자도
            // getter 로 보기 때문에 전부 꺼져 「{ }」가 나온다 (2026-09-10 확인).
            //
            // 그래서 방어는 이름 규칙이고, 그 규칙을 지키는 것이 이 테스트다.
            // 위의 왕복 테스트도 같은 것을 잡지만, 깨졌을 때 「왜」가 여기 적혀 있어야
            // 다음 사람이 원인을 다시 알아내지 않는다.
            String json = codec.toJson(Recordings.full());

            assertThat(json)
                    .as("Integrity.hasGaps() 는 record 의 구성 요소가 아니다")
                    .doesNotContain("\"incomplete\"")
                    .doesNotContain("\"gaps\"");
            assertThat(json)
                    .as("record 구성 요소는 그대로 있어야 한다")
                    .contains("\"droppedEvents\"");
        }

        @Test
        @DisplayName("JSON 에 null 필드가 실제로 적힌다")
        void nullsAreWrittenNotOmitted() {
            String json = codec.toJson(Recordings.mostlyUnknown());

            assertThat(json)
                    .as("null 을 생략하면 파일만 봤을 때 「못 알아냈다」와 「그런 게 없었다」가 같아 보인다")
                    .contains("\"gitCommit\" : null");
        }
    }

    @Nested
    @DisplayName("판 번호 — 모르면 거부한다")
    class SchemaVersion {

        @Test
        @DisplayName("아는 것보다 높은 판은 대충 읽지 않고 멈춘다")
        void refusesNewerSchema() {
            String json = codec.toJson(Recordings.full())
                    .replaceFirst("\"schemaVersion\" : 1", "\"schemaVersion\" : 99");

            assertThatThrownBy(() -> codec.fromJson(json))
                    .isInstanceOf(RecordingCodec.UnreadableRecordingException.class)
                    .hasMessageContaining("99")
                    .hasMessageContaining("Hindsight 를 올려라");
        }

        @Test
        @DisplayName("판 번호가 아예 없으면 멈춘다")
        void refusesMissingSchema() {
            assertThatThrownBy(() -> codec.fromJson("{\"id\":\"x\"}"))
                    .isInstanceOf(RecordingCodec.UnreadableRecordingException.class)
                    .hasMessageContaining("schemaVersion");
        }

        @Test
        @DisplayName("모르는 필드가 있으면 조용히 버리지 않고 멈춘다")
        void refusesUnknownField() {
            String json = codec.toJson(Recordings.full())
                    .replaceFirst("\\{", "{ \"뭔가새로생긴필드\" : 1,");

            assertThatThrownBy(() -> codec.fromJson(json))
                    .as("조용히 버리면 쓰는 쪽과 읽는 쪽이 갈라진 것을 몇 주 동안 아무도 모른다")
                    .isInstanceOf(RecordingCodec.UnreadableRecordingException.class);
        }
    }

    @Nested
    @DisplayName("자동 PR 조건")
    class AutoPullRequest {

        @Test
        @DisplayName("🔴 실측으로 확인됐고 기준선이 실패했을 때만 열린다")
        void opensOnlyWhenVerifiedAndBaselineFailed() {
            assertThat(replay(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, true).allowsAutoPullRequest())
                    .isTrue();

            assertThat(replay(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, false).allowsAutoPullRequest())
                    .as("패치 전에 실패하지 않은 테스트는 채점을 못 한다. 통과해도 아무 뜻이 없다")
                    .isFalse();

            assertThat(replay(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, null).allowsAutoPullRequest())
                    .as("기준선을 아직 안 돌려본 것을 「통과했다」로 치지 않는다")
                    .isFalse();

            assertThat(replay(ReplayInfo.Grade.PARTIAL, true).allowsAutoPullRequest())
                    .as("못 잡은 게 있으면 사람이 본다")
                    .isFalse();

            assertThat(replay(ReplayInfo.Grade.DIVERGED, true).allowsAutoPullRequest())
                    .as("패치가 질의 모양을 바꿨으면 재생으로는 채점할 수 없다")
                    .isFalse();
        }

        @Test
        @DisplayName("🔴 상태를 안 되돌리고 얻은 「같았다」로는 안 열린다")
        void doesNotOpenWhenStateWasNotRestored() {
            assertThat(new ReplayInfo(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, null, true, null,
                    null, null, null).allowsAutoPullRequest())
                    .as("🔴 stateRestore 가 null 인 것은 「되돌렸다」가 아니라 «안 봤다» 이다")
                    .isFalse();

            assertThat(new ReplayInfo(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, null, true, null,
                    new ReplayInfo.StateRestore(true, false, null, null), null, null).allowsAutoPullRequest())
                    .as("행만 되돌린 것은 되돌린 게 아니다 — id 가 어긋나서 재생이 죽는다")
                    .isFalse();

            assertThat(new ReplayInfo(ReplayInfo.Grade.VERIFIED_DETERMINISTIC, null, true, null,
                    new ReplayInfo.StateRestore(true, true, null, null), null, null).allowsAutoPullRequest())
                    .as("행과 카운터를 되돌렸으면 v0 이 되돌릴 수 있는 만큼은 다 되돌린 것이다")
                    .isTrue();
        }

        /** 복원선을 넘긴 기록. 자동 PR 조건에서 «복원 말고» 무엇이 남는지를 보려고 고정한다. */
        private ReplayInfo replay(ReplayInfo.Grade grade, Boolean baselineFailed) {
            return new ReplayInfo(grade, null, baselineFailed, null,
                    new ReplayInfo.StateRestore(true, true, null, null), null, null);
        }
    }
}
