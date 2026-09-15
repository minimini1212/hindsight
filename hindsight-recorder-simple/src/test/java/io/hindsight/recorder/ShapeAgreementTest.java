package io.hindsight.recorder;

import io.hindsight.core.replay.ReplayObservation;
import io.hindsight.core.store.RecordingCodec;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;
import io.hindsight.core.replay.ReplayGrader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>기록하는 쪽과 재생하는 쪽이 질의 모양을 «같게» 계산하는지 본다.</b>
 *
 * <h2>이 검사가 없으면 무슨 일이 나나</h2>
 * 질의 모양의 지문은 두 곳에서 쓰이고, 그 둘은 서로 대조된다.
 *
 * <pre>
 *   기록기가   질의를 모양으로 접어 요약층에 넣고          (관측 대상 앱 «안»)
 *   재생이     「기록에 없는 질의가 생겼나」를 그걸로 본다   (우리 쪽 «밖»)
 * </pre>
 *
 * <p>계산이 조금이라도 어긋나면 <b>모든 질의가 「기록에 없는 것」으로 보인다.</b>
 * 🔴 그러면 <b>모든 재생이 {@code DIVERGED} 가 되어 아무것도 채점하지 못한다.</b>
 * 그런데 오류는 하나도 안 난다 — 도구가 조용히 쓸모없어진다.
 *
 * <p>2026-09-15 에 실제로 그 직전까지 갔다. 지문 계산이 기록기 모듈 안에만 있어서
 * {@code hindsight-core} 가 쓸 수 없었고, 재생 쪽에 <b>두 번째 구현</b>을 만들 참이었다.
 * {@link SqlShapes} 로 옮겨 한 벌로 만들었고, 이 검사가 그 상태를 지킨다.
 */
@DisplayName("🔴 기록하는 쪽과 재생하는 쪽이 같은 모양을 만든다")
class ShapeAgreementTest {

    /** 되돌리기는 이 시험의 관심사가 아니다. 「충분히 되돌렸다」로 두고 모양만 본다. */
    private static final ReplayInfo.StateRestore 되돌림 =
            new ReplayInfo.StateRestore(true, true, null, null);

    @TempDir
    Path storeDir;

    private Recorder recorder;

    @AfterEach
    void 정리() {
        if (recorder != null) {
            recorder.close();
        }
    }

    /** 앱이 N+1 을 일으켰다고 치고, 진짜 기록 파일을 하나 만든다. */
    private Recording n플러스원을_기록한다() {
        recorder = new Recorder(RecorderConfig.builder()
                .storeDir(storeDir).appName("shape-agreement").build());

        recorder.recordSql("select * from orders", List.of(), null, 5);
        for (int i = 0; i < 20; i++) {
            recorder.recordSql("select * from member where id = " + i, List.of(), null, 2);
        }
        Path file = recorder.capture(Trigger.Kind.LATENCY, "GET /api/orders", null, 3400L, null)
                .orElseThrow();
        return new RecordingCodec().read(file);
    }

    @Test
    @DisplayName("기록기가 넣은 지문과 재생이 계산한 지문이 글자까지 같다")
    void 지문이_글자까지_같다() {
        Recording recording = n플러스원을_기록한다();

        // 기록기가 요약층에 넣어 둔 지문
        List<String> 기록쪽 = recording.summary().sqlShapes().stream()
                .map(Summary.SqlShape::sqlHash).sorted().toList();

        // 재생 쪽이 같은 질의로 계산한 지문
        List<String> 재생쪽 = new ArrayList<>();
        재생쪽.add(SqlShapes.of("select * from orders").hash());
        재생쪽.add(SqlShapes.of("select * from member where id = 999").hash());
        재생쪽 = 재생쪽.stream().sorted().toList();

        assertThat(기록쪽).isEqualTo(재생쪽);
    }

    @Test
    @DisplayName("🔴 기록과 «같은» 질의를 다시 내면 갈라졌다고 하지 않는다")
    void 같은_질의는_갈라짐이_아니다() {
        Recording recording = n플러스원을_기록한다();

        List<String> 재생질의 = new ArrayList<>();
        재생질의.add("select * from orders");
        for (int i = 100; i < 120; i++) {           // 값은 다르지만 «모양»은 같다
            재생질의.add("select * from member where id = " + i);
        }

        ReplayInfo info = ReplayGrader.grade(recording,
                ReplayObservation.builder().executedSql(재생질의).build(), 되돌림, true);

        // 🔴 여기가 핵심이다. 두 쪽 계산이 어긋나 있으면 여기가 DIVERGED 가 되고,
        //    그 순간 «모든» 재생이 채점 불가가 된다.
        assertThat(info.diverged()).isNull();
        assertThat(info.grade()).isNotEqualTo(ReplayInfo.Grade.DIVERGED);
    }

    @Test
    @DisplayName("진짜로 다른 질의를 내면 갈라졌다고 한다 — 검사가 그냥 통과만 하는 게 아니다")
    void 진짜_다른_질의는_갈라짐이다() {
        Recording recording = n플러스원을_기록한다();

        ReplayInfo info = ReplayGrader.grade(recording,
                ReplayObservation.builder()
                        .executedSql(List.of("select * from orders o join member m on o.member_id = m.id"))
                        .build(), 되돌림, true);

        assertThat(info.grade()).isEqualTo(ReplayInfo.Grade.DIVERGED);
        assertThat(info.diverged().before().count()).isEqualTo(20);
        assertThat(info.diverged().after().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 값만 다른 질의는 기록기 쪽에서도 «한 모양»으로 접힌다")
    void 기록기도_값만_다르면_한_모양으로_접는다() {
        Recording recording = n플러스원을_기록한다();

        // 질의는 21건 냈지만 모양은 둘이다.
        assertThat(recording.summary().sqlShapes()).hasSize(2);
        assertThat(recording.summary().sqlShapes())
                .anyMatch(shape -> shape.count() == 20)
                .anyMatch(shape -> shape.count() == 1);
    }
}
