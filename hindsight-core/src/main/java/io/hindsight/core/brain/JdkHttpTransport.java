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

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(연결_제한).build();

    @Override
    public 응답 보낸다(String url, Map<String, String> headers, String body) {
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
