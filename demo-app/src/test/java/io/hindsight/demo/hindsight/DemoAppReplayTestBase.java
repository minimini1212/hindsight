package io.hindsight.demo.hindsight;

import io.hindsight.core.replay.ReplayTestBase;
import io.hindsight.core.replay.StateSnapshot;
import io.hindsight.model.Event;
import io.hindsight.model.ReplayInfo;
import io.hindsight.model.SqlShapes;
import io.hindsight.recorder.Recorder;
import io.hindsight.recorder.ReplayProbe;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 🔴 <b>생성된 재생 테스트가 이 앱에서 실제로 돌게 해 주는 자리.</b>
 *
 * <p>생성기는 「무엇을 단언하는가」만 만든다. 「어떻게 되돌리고 어떻게 보내는가」는
 * 앱마다 다르므로 앱이 채운다 — 이 클래스가 그 채운 것이다.
 *
 * <h2>🔴 왜 스프링 빈이 아니라 «정적 자리»에서 받아 오나</h2>
 * 생성된 테스트는 <b>실행 시점에 만들어져 컴파일되는</b> 클래스다. 스프링 테스트 문맥이
 * 그런 클래스를 알 리 없고, 알게 만들려면 문맥을 다시 띄워야 한다 —
 * 그러면 <b>되돌려 놓은 DB 가 그 사이에 다시 바뀐다.</b>
 *
 * <p>그래서 바깥 시험이 {@link #준비한다} 로 도구를 걸어 두고, 생성된 테스트는 그걸 쓴다.
 * ⚠️ <b>이 방식은 한 번에 재생 하나만 돌릴 수 있다.</b> v0 는 그렇게만 쓰므로 괜찮고,
 * 여러 개를 동시에 돌리게 되면 이 자리부터 바꿔야 한다.
 */
public abstract class DemoAppReplayTestBase extends ReplayTestBase {

    /** 바깥 시험이 걸어 두는 도구 한 벌. */
    public record 재생도구(TestRestTemplate rest, Recorder recorder, StateSnapshot.Handle 떠둔것) {}

    private static 재생도구 도구;

    public static void 준비한다(재생도구 준비물) {
        도구 = 준비물;
    }

    public static void 치운다() {
        도구 = null;
    }

    @Override
    protected ReplayInfo.StateRestore 되돌린다() {
        요구한다();
        return 도구.떠둔것().restoreAndVerify();
    }

    @Override
    protected Observed 재생한다(String method, String path, String body) {
        요구한다();

        // 🔴 재생 «중»에도 같은 기록기가 보고 있다. 등급은 주장이 아니라 관찰이어야 한다.
        ReplayProbe probe = ReplayProbe.open(도구.recorder());

        HttpHeaders headers = new HttpHeaders();
        HttpEntity<String> entity;
        if (body == null) {
            // 🔴 「본문 없음」을 빈 문자열로 바꾸지 않는다. 그건 다른 요청이다.
            entity = new HttpEntity<>(headers);
        } else {
            headers.setContentType(MediaType.APPLICATION_JSON);
            entity = new HttpEntity<>(body, headers);
        }

        Throwable 나간예외 = null;
        ResponseEntity<String> response = null;
        try {
            response = 도구.rest().exchange(path, HttpMethod.valueOf(method), entity, String.class);
        } catch (RuntimeException e) {
            나간예외 = e;
        }

        var 관찰 = probe.observed(
                response == null ? null : response.getStatusCode().value(),
                response == null ? null : response.getBody(),
                나간예외);

        return new Observed(
                관찰.responseStatus() == null ? -1 : 관찰.responseStatus(),
                관찰.responseBody(),
                관찰.escaped() instanceof io.hindsight.core.replay.ReplayObservation.EscapedException.Thrown t
                        ? t.type() : null,
                가장_많이_반복된_모양의_횟수(관찰.executedSql()));
    }

    /**
     * 🔴 <b>이 요청이 낸</b> 질의 중 같은 모양이 최대 몇 번 반복됐나.
     *
     * <p>관찰이 이미 「이 재생이 낸 것」만 들고 있으므로 여기서는 모양으로 접어 세기만 한다.
     * 🔴 다른 요청이나 앱 시작 시의 질의를 같이 세면 숫자가 통째로 틀린다 —
     * 2026-09-15 에 실제로 그렇게 세다가 <b>버그가 그대로인데 통과</b>가 나왔다.
     */
    private static int 가장_많이_반복된_모양의_횟수(java.util.List<String> executedSql) {
        if (executedSql == null) {
            return 0;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String sql : executedSql) {
            counts.merge(SqlShapes.of(sql).hash(), 1, Integer::sum);
        }
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }

    private static void 요구한다() {
        if (도구 == null) {
            // 🔴 조용히 「되돌렸다」로 넘어가면, 되돌리지 않은 자리에서 잰 결과가
            //    패치 탓으로 읽힌다. 그럴 바엔 시끄럽게 죽는다.
            throw new IllegalStateException(
                    "재생 도구가 안 걸려 있다. 바깥 시험이 DemoAppReplayTestBase.준비한다(...) 를 먼저 불러야 한다.");
        }
    }

    /** 생성된 테스트가 이벤트를 세는 데 쓰지는 않지만, 기록 쪽과 같은 계산임을 보이려고 남긴다. */
    static int 모양별_최대반복(java.util.List<Event.Sql> queries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        queries.forEach(q -> counts.merge(SqlShapes.of(q.sql()).hash(), 1, Integer::sum));
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
