package io.hindsight.recorder.http;

import io.hindsight.model.Event;
import io.hindsight.model.Trigger;
import io.hindsight.recorder.Correlation;
import io.hindsight.recorder.Recorder;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 들어온 요청과 나간 응답을 잡고, 사고가 나면 기록을 떨구는 자리.
 *
 * <h2>v0 이 {@code Filter} 인 이유, 그리고 v1 이 아닌 이유</h2>
 * v0 은 앱의 소스를 고쳐서 이 필터를 등록한다 — 아무 앱에나 못 붙지만 <b>오늘 된다</b>.
 * v1 의 진짜 에이전트는 {@code DispatcherServlet#doService} 를 이름으로 잡는다.
 * 🔴 그때 {@code Filter} «인터페이스»로 잡으면 안 된다 — 인터페이스라서 앱의 모든 필터가
 * 걸리고, 스프링 시큐리티가 있으면 열다섯 개다. <b>같은 요청이 열다섯 번 겹쳐 기록된다.</b>
 *
 * <h2>방아쇠 두 개</h2>
 * <ul>
 *   <li><b>예외</b> — 진입점 밖으로 예외가 나갔다</li>
 *   <li><b>지연</b> — 진입점이 임계값(기본 3초)보다 오래 걸렸다</li>
 * </ul>
 * 🔴 예외가 난 경우 {@code finally} 에서 기록한다. 사고가 났을 때 기록이 없으면
 * 이 도구는 존재 이유가 없다.
 *
 * <h2>🔴 이 필터에서 나간 예외는 앱으로 새면 안 된다</h2>
 * 단 하나 예외가 있다. <b>앱이 던진 예외는 그대로 통과시킨다.</b> 그건 우리 것이 아니고,
 * 우리가 삼키면 앱의 오류 처리가 통째로 안 돈다 — 그것이야말로 앱의 동작을 바꾸는 것이다.
 * 우리 «자신의» 실패만 삼킨다.
 */
public final class RecordingFilter implements Filter {

    /** 한 요청에 두 번 돌지 않게 하는 표시. forward·include 때 필터가 다시 불릴 수 있다. */
    private static final String ALREADY_RAN = RecordingFilter.class.getName() + ".ran";

    private final Recorder recorder;

    public RecordingFilter(Recorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getAttribute(ALREADY_RAN) != null) {
            chain.doFilter(request, response);
            return;
        }
        request.setAttribute(ALREADY_RAN, Boolean.TRUE);

        int bodyMax = recorder.config().bodyMaxBytes();
        CachingRequest wrappedRequest = new CachingRequest(http, bodyMax);
        CachingResponse wrappedResponse = new CachingResponse(httpResponse, bodyMax);

        String entryPoint = http.getMethod() + " " + http.getRequestURI();
        Correlation.begin();
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();

        Throwable failure = null;
        try {
            chain.doFilter(wrappedRequest, wrappedResponse);
        } catch (Throwable t) {
            // 🔴 잡아 «두기만» 하고 다시 던진다. 삼키면 앱의 오류 처리가 안 돈다.
            failure = t;
            throw t;
        } finally {
            long tookMs = (System.nanoTime() - startedNanos) / 1_000_000;
            try {
                wrappedResponse.copyToRealResponse();
                Event.HttpIn event = toEvent(http, wrappedRequest, wrappedResponse, startedAt, tookMs);
                recorder.recordHttp(event);
                trigger(entryPoint, failure, tookMs);
            } catch (Throwable ourFailure) {
                // 🔴 여기서 나가는 예외는 «우리» 것이다. 앱으로 내보내지 않는다.
                //    앱이 던진 예외(failure)는 위의 throw 로 이미 나갔다.
            } finally {
                // 🔴 안 지우면 다음 요청이 이 번호를 물려받아 두 요청이 한 기록으로 합쳐진다.
                Correlation.clear();
            }
        }
    }

    private void trigger(String entryPoint, Throwable failure, long tookMs) {
        if (failure != null) {
            Throwable cause = rootCauseOf(failure);
            recorder.capture(
                    Trigger.Kind.EXCEPTION,
                    entryPoint,
                    new Trigger.ExceptionInfo(
                            cause.getClass().getName(),
                            cause.getMessage(),
                            stackOf(cause)),
                    tookMs,
                    stackSignature(cause));
            return;
        }
        if (tookMs >= recorder.config().latencyTriggerMs()) {
            // 🔴 지연 방아쇠에는 예외가 «없다». 그래서 묶는 열쇠를 예외 종류로 쓰면 안 된다 —
            //    서로 다른 느린 API 열 개가 한 건으로 접혀서 기록이 하나만 남는다.
            recorder.capture(Trigger.Kind.LATENCY, entryPoint, null, tookMs, null);
        }
    }

    private Event.HttpIn toEvent(HttpServletRequest original,
                                 CachingRequest request,
                                 CachingResponse response,
                                 Instant startedAt,
                                 long tookMs) {
        return new Event.HttpIn(
                recorder.nextSeq(),
                Correlation.current(),
                startedAt,
                Thread.currentThread().getName(),
                tookMs,
                original.getMethod(),
                original.getRequestURI(),
                queryOf(original),
                // 🔴 어느 컨트롤러가 받았는지는 v0 이 «안 본다». 알려면 스프링에 물어야 하고,
                //    그러면 이 모듈이 스프링을 의존하게 된다. null 이 그 사실이다.
                null,
                headersOf(original),
                bodyOf(request),
                request.truncated(),
                (int) Math.min(request.readBytes(), Integer.MAX_VALUE),
                response.getStatus(),
                new String(response.capturedBody(), StandardCharsets.UTF_8),
                response.truncated());
    }

    /**
     * 🔴 앱이 본문을 안 읽었으면 {@code null} 이다. 빈 문자열로 적으면
     * 「본문이 비어 있었다」가 되어, 재생이 빈 본문으로 요청을 보낸다.
     */
    private static String bodyOf(CachingRequest request) {
        if (!request.bodyWasRead()) {
            return null;
        }
        return new String(request.capturedBody(), StandardCharsets.UTF_8);
    }

    private static Map<String, String> queryOf(HttpServletRequest request) {
        String raw = request.getQueryString();
        if (raw == null) {
            return null; // 「쿼리를 안 봤다」가 아니라 「쿼리가 없었다」— 하지만 구분이 필요 없다
        }
        Map<String, String> query = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                query.put(pair, "");
            } else {
                query.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return Map.copyOf(query);
    }

    private static Map<String, String> headersOf(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return null;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(), request.getHeader(name));
        }
        // 🔴 여기서 Authorization·Cookie 를 «안» 지운다. 지우는 일은 파일로 쓰기 직전
        //    한 곳(Pseudonymizer)에서만 한다. 여기서도 지우면 「두 곳에서 지운다」가 되고,
        //    그러면 언젠가 한 곳만 고쳐져서 어느 쪽이 도는지 아무도 모르게 된다.
        return Map.copyOf(headers);
    }

    /**
     * 껍데기 예외를 벗기고 «진짜» 원인을 찾는다.
     *
     * <h2>🔴 이걸 안 하면 기록이 쓸모없어진다 — 실측으로 발견했다</h2>
     * 서블릿 컨테이너와 스프링은 컨트롤러가 던진 예외를
     * {@code jakarta.servlet.ServletException} 으로 <b>감싸서</b> 필터에게 준다.
     * 벗기지 않으면 모든 기록의 방아쇠가 {@code ServletException} 이 되고,
     * 묶는 열쇠도 전부 같아진다 — <b>서로 다른 버그가 전부 한 사고로 접혀서
     * 두 번째부터는 기록이 아예 안 만들어진다.</b>
     *
     * <p>2026-09-15 실험 ⑨ 를 돌리다가 나왔다. 그 전까지는 기록기를 따로 떼어 시험했고,
     * 거기서는 우리가 예외를 직접 만들어 넘겼기 때문에 <b>감싸는 일이 일어나지 않았다.</b>
     * 진짜 앱에 붙여 봐야 보이는 종류의 결함이다.
     *
     * <p>고리가 생긴 예외(자기 자신이 원인인 경우)에 대비해 깊이를 제한한다.
     */
    private static Throwable rootCauseOf(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; depth < 10; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                return current;
            }
            current = cause;
        }
        return current;
    }

    private static List<String> stackOf(Throwable failure) {
        List<String> frames = new ArrayList<>();
        for (StackTraceElement element : failure.getStackTrace()) {
            frames.add(element.toString());
            if (frames.size() >= 50) {
                break;
            }
        }
        return List.copyOf(frames);
    }

    /**
     * 같은 사고를 묶는 열쇠의 재료. 🔴 <b>예외 종류가 아니라 「어디서 났나」</b>다.
     *
     * <p>같은 {@code NullPointerException} 이라도 난 자리가 다르면 다른 사고다.
     * 종류로만 묶으면 서로 다른 버그 다섯 개가 기록 하나로 접힌다.
     */
    private static String stackSignature(Throwable failure) {
        StackTraceElement[] stack = failure.getStackTrace();
        if (stack.length == 0) {
            return failure.getClass().getName();
        }
        StackTraceElement top = stack[0];
        return top.getClassName() + "." + top.getMethodName() + ":" + top.getLineNumber();
    }
}
