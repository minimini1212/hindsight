package io.hindsight.core.cli;

import io.hindsight.model.Event;
import io.hindsight.model.Integrity;
import io.hindsight.model.Recording;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.Summary;
import io.hindsight.model.Trigger;

import java.util.List;

/**
 * 기록을 사람이 읽는 글로 바꾼다. 🔴 <b>순수 함수다</b> — 파일도 화면도 안 건드린다.
 *
 * <h2>🔴 이 화면이 존재하는 이유</h2>
 * 기록 파일은 JSON 이고, 사람이 열면 수백 줄이다. 그 안에서 <b>「이 기록을 믿어도 되나」</b>를
 * 찾아 읽는 것은 아무도 안 한다. 그래서 그 답을 <b>맨 위에 눈에 띄게</b> 올린다.
 *
 * <h2>🔴 담긴 시간이 짧으면 «눈에 띄게» 경고한다</h2>
 * 설정에 60초라고 써 있어도 트래픽이 많으면 실제로는 4초치만 담긴다.
 * 이때 조용히 넘어가면, 읽는 사람은 <b>30초 전에 시작된 커넥션 누수가 일어나지 않았다고
 * 결론 내린다.</b> 안 담긴 것을 안 담겼다고 말하지 않으면 「없었다」와 「못 담았다」가
 * 똑같아 보인다 — 이 프로젝트가 막으려는 바로 그 결함이다.
 */
public final class CliRenderer {

    private CliRenderer() {}

    /** 한 줄이 기록 하나. 최근 것이 위로 온다. */
    public static String renderList(List<Recording> recordings) {
        if (recordings.isEmpty()) {
            return "기록이 없다.\n";
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format("%-10s %-20s %-9s %-28s %s%n",
                "번호", "언제", "방아쇠", "진입점", "온전한가"));
        out.append("─".repeat(92)).append('\n');

        for (Recording recording : recordings) {
            Trigger trigger = recording.trigger();
            out.append(String.format("%-10s %-20s %-9s %-28s %s%n",
                    nz(recording.id()),
                    recording.capturedAt() == null ? "(모름)" : recording.capturedAt().toString().substring(0, 19),
                    trigger == null ? "(없음)" : trigger.kind().name(),
                    trigger == null ? "(모름)" : 줄여서(trigger.entryPoint(), 28),
                    온전함_한줄(recording.integrity())));
        }
        out.append('\n').append(경고_모음(recordings));
        return out.toString();
    }

    /** 기록 하나를 자세히. */
    public static String renderShow(Recording recording) {
        StringBuilder out = new StringBuilder();

        out.append("기록 ").append(nz(recording.id()))
                .append("   ").append(recording.capturedAt()).append("\n\n");

        // 🔴 「이 기록을 믿어도 되나」가 맨 위에 온다. 아래에 묻히면 아무도 안 읽는다.
        out.append(온전함_자세히(recording.integrity()));
        out.append(재생_자세히(recording.replay()));

        Trigger trigger = recording.trigger();
        out.append("── 무엇이 이 기록을 만들었나 ──\n");
        if (trigger == null) {
            out.append("🔴 방아쇠가 «없다». 무엇이 이 기록을 만들었는지 모른다.\n\n");
        } else {
            out.append("  ").append(trigger.kind()).append("  ").append(nz(trigger.entryPoint())).append('\n');
            if (trigger.exception() != null) {
                out.append("  예외   ").append(trigger.exception().type());
                if (trigger.exception().message() != null) {
                    out.append(" — ").append(trigger.exception().message());
                }
                out.append('\n');
            }
            if (trigger.latencyMs() != null) {
                out.append("  소요   ").append(trigger.latencyMs()).append("ms\n");
            }
            if (trigger.dedupCount() > 1) {
                // 🔴 「한 번 났다」와 「1,204번 중 하나다」는 다른 사실이다.
                out.append("  ⚠️ 같은 사고가 ").append(trigger.dedupCount())
                        .append("번 났다. 이 기록은 그중 첫 번째다\n");
            }
            out.append('\n');
        }

        long 전문층의_SQL수 = recording.events() == null ? 0
                : recording.events().stream().filter(Event.Sql.class::isInstance).count();
        out.append(이벤트_요약(recording));
        out.append(질의_모양(recording.summary(), 전문층의_SQL수));
        return out.toString();
    }

    // ── 온전함 ──────────────────────────────────────────────────────────────

    private static String 온전함_한줄(Integrity integrity) {
        if (integrity == null) {
            return "🔴 모름";
        }
        if (integrity.windowFellShort()) {
            return String.format("🔴 %.1f/%d초", integrity.windowActualSeconds(), integrity.windowRequestedSeconds());
        }
        if (integrity.hasGaps()) {
            return "⚠️ 구멍 있음";
        }
        return "✅";
    }

    private static String 온전함_자세히(Integrity integrity) {
        StringBuilder out = new StringBuilder("── 이 기록이 온전한가 ──\n");
        if (integrity == null) {
            out.append("🔴 «안 적혀 있다». 무엇을 못 담았는지 알 수 없다.\n\n");
            return out.toString();
        }

        // 🔴 여기가 이 화면에서 가장 중요한 줄이다.
        if (integrity.windowFellShort()) {
            out.append("🔴 담으려던 ").append(integrity.windowRequestedSeconds())
                    .append("초 중 실제로는 ").append(String.format("%.1f", integrity.windowActualSeconds()))
                    .append("초만 담겼다.\n");
            out.append("   그 앞에서 시작된 일은 이 기록에 «없다» — 커넥션 누수처럼 30초 전에\n");
            out.append("   시작된 것을 찾고 있다면, 여기서 「없었다」고 결론 내리면 안 된다.\n");
        } else {
            out.append("  담긴 시간  ").append(String.format("%.1f", integrity.windowActualSeconds()))
                    .append("초 / 담으려던 ").append(integrity.windowRequestedSeconds()).append("초\n");
        }

        if (integrity.droppedEvents() > 0) {
            out.append("🔴 큐가 차서 ").append(integrity.droppedEvents())
                    .append("건을 «못 받았다». 그중에 원인이 있었을 수 있다\n");
        }
        if (integrity.evictedEvents() > 0) {
            out.append("⚠️ 버퍼가 넘쳐 ").append(integrity.evictedEvents()).append("건이 밀려났다\n");
        }
        if (integrity.instrumentationDisabled()) {
            out.append("🔴 기록 중 계측이 «스스로 꺼졌다». 그 뒤는 비어 있는 게 아니라 안 담긴 것이다\n");
        }
        if (integrity.agentErrors() > 0) {
            out.append("⚠️ 기록기 내부에서 삼킨 예외 ").append(integrity.agentErrors()).append("건\n");
        }
        if (!integrity.hasGaps() && !integrity.windowFellShort()) {
            out.append("  ✅ 못 담은 것이 없다\n");
        }
        return out.append('\n').toString();
    }

    private static String 재생_자세히(ReplayInfo replay) {
        StringBuilder out = new StringBuilder("── 재생 ──\n");
        if (replay == null) {
            // 🔴 「재생한 적 없다」이지 「재생이 실패했다」가 아니다.
            out.append("  아직 재생한 적이 «없다»\n\n");
            return out.toString();
        }
        out.append("  등급  ").append(replay.grade()).append('\n');
        if (replay.missing() == null) {
            out.append("  🔴 무엇을 못 잡았는지 «모른다»\n");
        } else if (!replay.missing().isEmpty()) {
            out.append("  못 잡은 것  ").append(String.join(", ", replay.missing())).append('\n');
        }
        if (replay.stateRestore() == null) {
            out.append("  🔴 재생 전에 무엇을 되돌렸는지 «안 봤다»\n");
        } else {
            out.append("  되돌림  행 ").append(참거짓(replay.stateRestore().rows()))
                    .append(" · 다음 id ").append(참거짓(replay.stateRestore().identityCounters()))
                    .append(" · 캐시 ").append(참거짓(replay.stateRestore().caches()))
                    .append(" · 외부 ").append(참거짓(replay.stateRestore().external())).append('\n');
        }
        if (replay.notes() != null) {
            out.append("  메모  ").append(replay.notes()).append('\n');
        }
        return out.append('\n').toString();
    }

    /** 🔴 {@code null} 을 「아니오」로 적지 않는다 — 「안 봤다」와 「보았고 아니었다」는 다르다. */
    private static String 참거짓(Boolean value) {
        if (value == null) {
            return "안 봤다";
        }
        return value ? "예" : "🔴 아니오";
    }

    // ── 이벤트 ──────────────────────────────────────────────────────────────

    private static String 이벤트_요약(Recording recording) {
        StringBuilder out = new StringBuilder("── 담긴 것 ──\n");
        if (recording.events() == null) {
            out.append("  🔴 이벤트가 «없다»(안 담겼다)\n\n");
            return out.toString();
        }
        long http = recording.events().stream().filter(Event.HttpIn.class::isInstance).count();
        long sql = recording.events().stream().filter(Event.Sql.class::isInstance).count();
        out.append("  들어온 요청 ").append(http).append("건 · SQL ").append(sql).append("건");
        out.append("  (전체 ").append(recording.events().size()).append("건)\n");

        recording.events().stream()
                .filter(Event.HttpIn.class::isInstance)
                .map(Event.HttpIn.class::cast)
                .forEach(e -> {
                    out.append("  ").append(e.method()).append(' ').append(e.path());
                    out.append("  → ").append(e.responseStatus() == null ? "(응답 모름)" : e.responseStatus());
                    if (e.durationMs() != null) {
                        out.append("  ").append(e.durationMs()).append("ms");
                    }
                    if (e.responseBody() == null) {
                        // 🔴 「본문이 비었다」가 아니라 「본문을 못 잡았다」다.
                        out.append("   🔴 응답 본문을 «못 잡았다»");
                    } else if (e.responseBodyTruncated()) {
                        out.append("   ⚠️ 응답 본문이 잘렸다");
                    }
                    out.append('\n');
                });
        return out.append('\n').toString();
    }

    /**
     * @param 전문층의_SQL수 전문층에 남아 있는 SQL 이벤트 수.
     *                   🔴 요약층의 횟수와 «다른 것»이고, 다르다는 사실을 화면이 설명해야 한다
     */
    private static String 질의_모양(Summary summary, long 전문층의_SQL수) {
        StringBuilder out = new StringBuilder("── 질의 모양 (많이 반복된 순) ──\n");
        if (summary == null || summary.sqlShapes() == null) {
            out.append("  «안 봤다»\n");
            return out.toString();
        }
        if (summary.sqlShapes().isEmpty()) {
            out.append("  보았고 하나도 없었다\n");
            return out.toString();
        }
        summary.sqlShapes().stream()
                .sorted((a, b) -> Integer.compare(b.count(), a.count()))
                .limit(10)
                .forEach(shape -> {
                    // 🔴 한 모양이 여러 번 반복되는 것이 N+1 의 서명이다. 그게 눈에 띄어야 한다.
                    String 표시 = shape.count() >= 10 ? "🔴" : shape.count() >= 3 ? "⚠️" : "  ";
                    out.append(String.format("  %s %4d번  %s%n", 표시, shape.count(),
                            줄여서(shape.normalized(), 70)));
                });
        long 요약층_합계 = summary.sqlShapes().stream().mapToLong(Summary.SqlShape::count).sum();
        if (요약층_합계 > 전문층의_SQL수) {
            // 🔴 두 층은 다른 것이다. 설명 없이 「SQL 0건」과 「20번」을 같이 보여 주면
            //    읽는 사람은 둘 중 하나가 틀렸다고 생각한다. 실제로는 둘 다 맞다 —
            //    전문층(짧고 무겁다)은 밀려났고, 요약층(길고 가볍다)은 남은 것이다.
            out.append("\n  ⚠️ 위의 「담긴 것」보다 여기 횟수가 많다. 둘 다 맞다 —\n");
            out.append("     전문(본문까지 담는 층)은 ").append(전문층의_SQL수)
                    .append("건만 남았고, 여기 요약(모양과 횟수만 담는 층)은 ")
                    .append(요약층_합계).append("건을 세었다.\n");
            out.append("     🔴 재생에 쓸 수 있는 것은 «전문»뿐이다. 요약은 사람이 원인을 찾는 데만 쓴다.\n");
        }
        if (summary.connectionSamples() == null) {
            out.append("\n  ⚠️ 커넥션 풀은 «안 봤다» — 누수를 찾고 있다면 여기서는 알 수 없다\n");
        }
        return out.toString();
    }

    // ── 조각 ────────────────────────────────────────────────────────────────

    private static String 경고_모음(List<Recording> recordings) {
        long 짧게담김 = recordings.stream()
                .filter(r -> r.integrity() != null && r.integrity().windowFellShort()).count();
        if (짧게담김 == 0) {
            return "";
        }
        return "🔴 " + 짧게담김 + "건이 담으려던 시간보다 «훨씬 짧게» 담겼다.\n"
                + "   그 앞에서 시작된 일은 그 기록에 없다. `hs show <번호>` 로 확인할 것.\n";
    }

    private static String 줄여서(String text, int max) {
        if (text == null) {
            return "(모름)";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static String nz(String text) {
        return text == null ? "(모름)" : text;
    }
}
