package io.hindsight.core.brain;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 진짜로 HTTP 를 보내는 하나뿐인 자리.
 *
 * <h2>🔴 라이브러리를 안 쓴다</h2>
 * JDK 의 {@code java.net.http} 로 충분하다. 여기에 HTTP 라이브러리를 넣으면
 * {@code core} 의 의존성이 늘고, {@code core} 는 {@code recorder-simple} 을 거쳐
 * <b>관측 대상 앱의 클래스패스</b>로 이어진다 — {@code checkRecorderDependencies} 가
 * 그 자리에서 울린다. picocli 때 이미 한 번 겪었다.
 *
 * <h2>🔴 예외를 던지지 않는다</h2>
 * 네트워크 실패는 «결과»다. 던져서 위로 올리면 「LLM 이 못 고쳤다」와
 * 「LLM 에 닿지도 못했다」가 같은 자리에서 뭉개진다.
 */
public final class JdkHttpTransport implements HttpDiagnosis.전송 {

    /** 🔴 시간 제한을 «반드시» 둔다. 없으면 답이 안 오는 날 고리가 영원히 멈춘다. */
    private static final Duration 연결_제한 = Duration.ofSeconds(15);
    private static final Duration 응답_제한 = Duration.ofSeconds(120);

    /**
     * 🔴 <b>「잠깐 안 되는 것」과 「영영 안 되는 것」은 다르다.</b>
     *
     * <p>2026-09-16 에 고리를 처음 끝까지 돌렸더니 첫 호출에서 이게 왔다.
     *
     * <pre>
     *   HTTP 503  "This model is currently experiencing high demand.
     *              Spikes in demand are usually temporary. Please try again later."
     * </pre>
     *
     * <p>서버가 <b>「나중에 다시 하라」고 말하고 있는데</b>, 그때의 코드는 그걸
     * 키가 틀린 것과 똑같이 다뤘다 — 고리가 그 자리에서 끝났다.
     *
     * <p>⚠️ 이건 <b>시도 예산과 다른 층</b>이다. 예산은 「LLM 에게 몇 번 «시킬» 것인가」이고,
     * 이건 「그 한 번을 «보내는» 데 몇 번 두드릴 것인가」다.
     * 🔴 다시 두드리는 것은 <b>예산을 안 깎는다</b> — 답을 못 받았으니 돈도 안 썼다.
     */
    private static final java.util.Set<Integer> 잠깐_안_되는_것 =
            java.util.Set.of(408, 429, 500, 502, 503, 504);

    /** 🔴 몇 번까지만. 서버가 계속 안 되면 그건 «지금은 안 되는 것»이고, 그렇게 보고한다. */
    static final int 다시_두드리는_횟수 = 3;

    /** 기다리는 시간. 두 배씩 늘린다 — 과부하일 때 같은 속도로 두드리면 과부하를 «키운다». */
    static final Duration 첫_기다림 = Duration.ofSeconds(2);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(연결_제한).build();

    @Override
    public 응답 보낸다(String url, Map<String, String> headers, String body) {
        Duration 기다림 = 첫_기다림;
        응답 마지막 = null;

        for (int 시도 = 1; 시도 <= 다시_두드리는_횟수; 시도++) {
            마지막 = 한번_보낸다(url, headers, body);
            if (마지막.ok() || !잠깐_안_되는_것.contains(마지막.status())) {
                return 마지막;
            }
            if (시도 == 다시_두드리는_횟수) {
                break;
            }
            try {
                Thread.sleep(기다림.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new 응답(0, null, "기다리다 중단됐다");
            }
            기다림 = 기다림.multipliedBy(2);
        }

        // 🔴 「몇 번 두드렸는데도 안 됐다」를 그대로 적는다. 한 번 만에 실패한 것과 다른 사실이다.
        return new 응답(마지막.status(), 마지막.body(),
                "HTTP " + 마지막.status() + " 가 " + 다시_두드리는_횟수
                        + "번 다 났다. 서버가 «지금은» 안 된다고 한다 — 조금 뒤에 다시 한다");
    }

    private 응답 한번_보낸다(String url, Map<String, String> headers, String body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(응답_제한)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(b::header);

            HttpResponse<String> r = client.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new 응답(r.statusCode(), r.body(), null);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new 응답(0, null, "기다리다 중단됐다");
        } catch (Exception e) {
            // 🔴 메시지에 주소는 남기되 «헤더는 안 남긴다» — 토큰이 거기 있다.
            return new 응답(0, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
