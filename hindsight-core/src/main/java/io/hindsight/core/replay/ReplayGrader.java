package io.hindsight.core.replay;

import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.SqlShapes;
import io.hindsight.model.Summary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 재생 한 번의 결과를 «등급»으로 매긴다. 🔴 <b>순수 함수다</b> — 파일도 DB 도 안 건드린다.
 *
 * <h2>등급은 「패치가 맞았나」가 아니라 「이 재생을 믿을 수 있나」다</h2>
 * 이 둘을 섞으면 판단이 통째로 망가진다. 축이 둘이다.
 *
 * <pre>
 *   등급(ReplayInfo.grade)   이 재생이 얼마나 믿을 만한가   ← 이 클래스가 정한다
 *   판정(OracleVerdict)      패치가 버그를 고쳤나           ← Oracle 이 정한다
 * </pre>
 *
 * 🔴 <b>믿을 수 없는 재생에서 나온 「통과」는 통과가 아니다.</b> 그래서 자동으로 PR 을 올릴지는
 * 등급까지 같이 본다({@link ReplayInfo#allowsAutoPullRequest()}).
 *
 * <h2>등급을 정하는 순서 — 위가 이긴다</h2>
 * <pre>
 *   ① 재생이 아예 안 돌았다            → FAILED     🔴 LLM 을 부르지 않는다
 *   ② 기록에 없는 질의를 만났다         → DIVERGED   통과도 실패도 아니다. 사람에게 넘긴다
 *   ③ 되돌리기가 모자랐다              → PARTIAL + missing:["STATE"]
 *   ④ 기록 자체에 구멍이 있었다         → PARTIAL + missing:[…]
 *   ⑤ 그 밖                          → VERIFIED_DETERMINISTIC
 * </pre>
 *
 * <h2>🔴 ③ 이 ⑤ 보다 위에 있는 이유</h2>
 * 되돌리지 않은 채 돌린 재생이 원본과 다른 것은 <b>패치 탓이 아니라 우리가 만든 차이</b>다.
 * 그걸 {@code DIVERGED} 나 {@code FAILED} 로 적으면 아무 잘못 없는 패치가 벌을 받는다.
 * 특히 <b>행만 되돌린 재생이 가장 위험하다</b> — 「다음 id 는 몇 번」이라는 숫자가 안 돌아와서
 * 재생이 예외로 죽고, 채점기는 그 죽음을 「패치가 못 고쳤다」로 읽는다.
 */
public final class ReplayGrader {

    /** 기록이 낡았다고 표시하기까지의 기본 기간. 막지는 않고 표시만 한다. */
    private static final Duration 기본_유효기간 = Duration.ofDays(30);

    private ReplayGrader() {}

    /**
     * @param stateRestore 재생 전에 «어디까지» 되돌렸나. 🔴 {@code null} 이면 「안 봤다」이고,
     *                     그건 「되돌렸다」가 아니다
     * @param baselineFailed 패치 «전» 코드에서 이 재생이 정말 실패했나.
     *                       🔴 {@code null} 이면 안 확인한 것이다
     */
    public static ReplayInfo grade(Recording recording,
                                   ReplayObservation observation,
                                   ReplayInfo.StateRestore stateRestore,
                                   Boolean baselineFailed) {

        List<String> notes = new ArrayList<>();

        // ① 재생이 아예 안 돌았다.
        if (observation.replayFailed()) {
            notes.add("재생이 성립하지 않았다: " + observation.failureReason());
            return new ReplayInfo(
                    ReplayInfo.Grade.FAILED,
                    // 🔴 무엇을 못 잡았는지 «모른다». 빈 목록으로 적으면 「보았고 없었다」가 된다.
                    null,
                    baselineFailed,
                    null,
                    stateRestore,
                    staleAfter(recording),
                    String.join(" / ", notes));
        }

        // ② 기록에 없는 질의를 만났나.
        ReplayInfo.Divergence divergence = findDivergence(recording, observation);
        if (divergence != null) {
            notes.add("재생이 기록에 없는 질의를 냈다. 채점 불가이고, 근거를 붙여 사람에게 넘긴다");
            return new ReplayInfo(
                    ReplayInfo.Grade.DIVERGED,
                    null,
                    baselineFailed,
                    divergence,
                    stateRestore,
                    staleAfter(recording),
                    String.join(" / ", notes));
        }

        // ③④ 못 잡은 것 · 못 되돌린 것을 모은다.
        Set<String> missing = new LinkedHashSet<>();

        if (stateRestore == null) {
            missing.add("STATE");
            notes.add("재생 전에 무엇을 되돌렸는지 «안 봤다». 되돌렸다고 칠 수 없다");
        } else if (!stateRestore.restoredEnoughToGrade()) {
            missing.add("STATE");
            notes.add(restoreNote(stateRestore));
        }

        missing.addAll(missingFromIntegrity(recording, notes));

        if (!missing.isEmpty()) {
            return new ReplayInfo(
                    ReplayInfo.Grade.PARTIAL,
                    List.copyOf(missing),
                    baselineFailed,
                    null,
                    stateRestore,
                    staleAfter(recording),
                    String.join(" / ", notes));
        }

        // ⑤ 전부 맞았다. 🔴 이건 «관찰»이지 약속이 아니다.
        return new ReplayInfo(
                ReplayInfo.Grade.VERIFIED_DETERMINISTIC,
                // 보았고 빠진 것이 없었다 → 빈 목록. 여기서는 [] 가 맞는 값이다.
                List.of(),
                baselineFailed,
                null,
                stateRestore,
                staleAfter(recording),
                notes.isEmpty() ? null : String.join(" / ", notes));
    }

    // ── ② 기록에 없는 질의 찾기 ─────────────────────────────────────────────

    /**
     * 재생이 낸 질의 중 기록에 «모양이 없는» 것을 찾는다.
     *
     * <h2>🔴 이게 이 프로젝트에서 가장 흥미로운 자리다</h2>
     * 이 도구가 겨누는 대표 버그인 N+1 을 <b>제대로</b> 고치면 질의 201개가 조인 1개로 바뀐다.
     * 그런데 그 새 질의는 기록에 없다. 즉 <b>「제대로 된 수정」일수록 재생이 깨진다.</b>
     * 이걸 실패로 처리하면 채점기가 얕은 패치를 구조적 수정보다 높게 친다 — 정확히 거꾸로다.
     *
     * <p>그래서 실패도 통과도 아닌 세 번째 결과를 만들고, <b>무엇이 무엇으로 바뀌었는지</b>를
     * 붙여 사람에게 넘긴다. 그게 가장 좋은 PR 설명이 된다.
     */
    static ReplayInfo.Divergence findDivergence(Recording recording, ReplayObservation observation) {
        if (observation.executedSql() == null) {
            return null; // 질의를 안 봤다. 「없었다」가 아니므로 갈라졌다고도 말할 수 없다.
        }
        Map<String, Summary.SqlShape> recorded = recordedShapes(recording);
        if (recorded.isEmpty()) {
            return null; // 견줄 기준이 없다.
        }

        Map<String, Integer> replayedCounts = new LinkedHashMap<>();
        Map<String, String> replayedNormalized = new LinkedHashMap<>();
        for (String sql : observation.executedSql()) {
            SqlShapes.Shape shape = SqlShapes.of(sql);
            replayedCounts.merge(shape.hash(), 1, Integer::sum);
            replayedNormalized.putIfAbsent(shape.hash(), shape.normalized());
        }

        String unknownHash = replayedCounts.keySet().stream()
                .filter(hash -> !recorded.containsKey(hash))
                .findFirst()
                .orElse(null);
        if (unknownHash == null) {
            return null;
        }

        // 기록에서 «가장 많이 반복된» 모양을 「무엇이 바뀌었나」의 앞쪽으로 둔다.
        // N+1 을 고친 경우 그게 바로 사라진 그 질의다.
        Summary.SqlShape before = recorded.values().stream()
                .max(java.util.Comparator.comparingInt(Summary.SqlShape::count))
                .orElse(null);

        int afterCount = replayedCounts.get(unknownHash);
        String verdict = (before != null && before.count() > afterCount)
                ? "질의 " + before.count() + "개가 " + afterCount + "개로 줄었다. N+1 해소와 부합한다"
                : "기록에 없는 질의가 생겼다. 무엇이 바뀐 것인지는 사람이 본다";

        return new ReplayInfo.Divergence(
                "SQL_SHAPE_CHANGED",
                before == null ? null : new ReplayInfo.Divergence.Side(before.sqlHash(), before.normalized(), before.count()),
                new ReplayInfo.Divergence.Side(unknownHash, replayedNormalized.get(unknownHash), afterCount),
                verdict);
    }

    /** 기록이 들고 있는 질의 모양. 요약층이 있으면 그걸 쓰고, 없으면 전문층에서 만든다. */
    private static Map<String, Summary.SqlShape> recordedShapes(Recording recording) {
        Map<String, Summary.SqlShape> shapes = new LinkedHashMap<>();

        Summary summary = recording.summary();
        if (summary != null && summary.sqlShapes() != null) {
            for (Summary.SqlShape shape : summary.sqlShapes()) {
                shapes.put(shape.sqlHash(), shape);
            }
        }
        if (!shapes.isEmpty() || recording.events() == null) {
            return shapes;
        }

        // 🔴 요약층이 없는 기록도 있다(v0 초기 파일). 그때 「모양이 하나도 없다」로 두면
        //    모든 질의가 「기록에 없는 것」이 되어 전부 DIVERGED 가 된다. 전문층에서 만든다.
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Event event : recording.events()) {
            if (event instanceof Event.Sql sql) {
                SqlShapes.Shape shape = SqlShapes.of(sql.sql());
                counts.merge(shape.hash(), 1, Integer::sum);
                normalized.putIfAbsent(shape.hash(), shape.normalized());
            }
        }
        counts.forEach((hash, count) ->
                shapes.put(hash, new Summary.SqlShape(hash, normalized.get(hash), count, 0)));
        return shapes;
    }

    // ── ④ 기록 자체의 구멍 ──────────────────────────────────────────────────

    /**
     * 기록이 「못 담았다」고 스스로 적은 것을 등급에 반영한다.
     *
     * <p>🔴 이걸 안 보면 <b>구멍 난 기록에서 나온 재생이 「전부 맞았다」로 적힌다.</b>
     * 큐가 차서 이벤트를 버린 기록은, 버린 그 이벤트가 사고의 원인이었을 수 있다.
     */
    private static Set<String> missingFromIntegrity(Recording recording, List<String> notes) {
        Set<String> missing = new LinkedHashSet<>();
        Integrity integrity = recording.integrity();
        if (integrity == null) {
            missing.add("INTEGRITY");
            notes.add("기록이 「무엇을 못 담았나」를 «안 적었다». 온전한지 알 수 없다");
            return missing;
        }
        if (integrity.droppedEvents() > 0) {
            missing.add("EVENTS");
            notes.add("큐가 차서 못 받은 이벤트가 " + integrity.droppedEvents() + "건 있다");
        }
        if (integrity.evictedEvents() > 0) {
            missing.add("EVENTS");
            notes.add("버퍼가 넘쳐 밀어낸 이벤트가 " + integrity.evictedEvents() + "건 있다");
        }
        if (integrity.instrumentationDisabled()) {
            missing.add("INSTRUMENTATION");
            notes.add("기록 중 계측이 스스로 꺼졌다");
        }
        if (integrity.windowFellShort()) {
            missing.add("WINDOW");
            notes.add("담으려던 " + integrity.windowRequestedSeconds() + "초 중 실제로는 "
                    + String.format("%.1f", integrity.windowActualSeconds()) + "초만 담겼다");
        }
        return missing;
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private static String restoreNote(ReplayInfo.StateRestore restore) {
        if (Boolean.TRUE.equals(restore.rows()) && !Boolean.TRUE.equals(restore.identityCounters())) {
            // 🔴 데이터 계약이 「가장 위험한 기록」이라고 부르는 모양이다.
            return "행은 되돌렸는데 「다음 id 는 몇 번」을 안 되돌렸다. "
                    + "부분 복원은 무복원보다 위험하다 — 재생이 죽고 그 죽음이 패치 탓으로 읽힌다";
        }
        return "재생 전 되돌리기가 모자랐다. 원본과의 차이는 패치가 아니라 우리가 만든 것일 수 있다";
    }

    private static Instant staleAfter(Recording recording) {
        Instant capturedAt = recording.capturedAt();
        return capturedAt == null ? null : capturedAt.plus(기본_유효기간);
    }
}
