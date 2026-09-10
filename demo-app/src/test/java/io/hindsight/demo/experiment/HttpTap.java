package io.hindsight.demo.experiment;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 들어온 요청과 나간 응답을 «중간에서» 잡아 두는 도구.
 *
 * <h2>🔴 여기에 이 실험의 진짜 함정이 있다 — 본문은 한 번만 읽을 수 있다</h2>
 * HTTP 요청 본문은 <b>흐르는 물</b>이다. {@code getInputStream()} 으로 한 번 읽으면 사라진다.
 * 그래서 Filter 가 「기록해 두려고」 본문을 읽어 버리면,
 * <b>정작 앱에는 빈 본문이 도착해서 요청이 실패한다.</b>
 *
 * <p>관측하려다 관측 대상을 망가뜨리는 것이고, 이 프로젝트가 절대 하면 안 되는 일이다.
 * 응답 본문도 똑같다 — 우리가 읽으면 클라이언트에게 안 간다.
 *
 * <h2>어떻게 푸나</h2>
 * 스프링이 주는 {@link ContentCachingRequestWrapper}·{@link ContentCachingResponseWrapper} 를 쓴다.
 * <b>흘러가는 물을 지나가는 김에 복사해 두는</b> 껍데기다. 앱은 원래대로 읽고,
 * 우리는 복사본을 본다.
 *
 * <pre>
 *   요청 ──▶ [요청 껍데기] ──▶ 앱     앱이 읽는 순간 복사본이 쌓인다
 *   응답 ◀── [응답 껍데기] ◀── 앱     🔴 마지막에 copyBodyToResponse() 를 «반드시» 불러야
 *                                       클라이언트에게 응답이 간다
 * </pre>
 *
 * <p>⚠️ 두 가지 제약이 남는다. 실험이 이걸 확인한다.
 * <ul>
 *   <li>복사본은 <b>앱이 실제로 읽은 만큼만</b> 쌓인다. 앱이 본문을 안 읽으면 우리도 못 본다</li>
 *   <li>{@code copyBodyToResponse()} 를 잊으면 응답이 통째로 사라진다 — 흔한 실수다</li>
 * </ul>
 */
public class HttpTap extends OncePerRequestFilter {

    /** 잡아 둔 한 건. 재생에 필요한 것이 다 들어 있어야 한다. */
    public record Captured(
            String method,
            String path,
            String query,
            Map<String, String> headers,
            String requestBody,
            int status,
            String responseBody
    ) {}

    private final List<Captured> captured = Collections.synchronizedList(new ArrayList<>());

    public List<Captured> captured() {
        return List.copyOf(captured);
    }

    public Captured last() {
        List<Captured> all = captured();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public void reset() {
        captured.clear();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            // 🔴 finally 인 이유: 앱이 예외를 던져도 기록은 남겨야 한다.
            //    사고가 났을 때 기록이 없으면 이 도구는 존재 이유가 없다.
            record(wrappedRequest, wrappedResponse);

            // 🔴 이 한 줄을 잊으면 클라이언트가 «빈 응답»을 받는다.
            //    우리가 복사해 둔 것을 진짜 응답으로 되돌려 보내는 일이다.
            wrappedResponse.copyBodyToResponse();
        }
    }

    private void record(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response) {
        Map<String, String> headers = new LinkedHashMap<>();
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            // 🔴 여기가 데이터 계약이 「무조건 버린다」고 정한 자리다.
            //    실험 단계지만 처음부터 지킨다 — 나중에 붙이면 그 사이 기록에 남는다.
            if (name.equalsIgnoreCase("authorization")
                    || name.equalsIgnoreCase("cookie")) {
                continue;
            }
            headers.put(name.toLowerCase(), request.getHeader(name));
        }

        captured.add(new Captured(
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                headers,
                new String(request.getContentAsByteArray(), StandardCharsets.UTF_8),
                response.getStatus(),
                new String(response.getContentAsByteArray(), StandardCharsets.UTF_8)
        ));
    }
}
