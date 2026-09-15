package io.hindsight.recorder.http;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 요청 본문을 <b>지나가는 김에</b> 복사해 두는 껍데기.
 *
 * <h2>🔴 여기에 이 기록기의 가장 위험한 함정이 있다 — 본문은 한 번만 읽을 수 있다</h2>
 * HTTP 요청 본문은 <b>흐르는 물</b>이다. {@code getInputStream()} 으로 한 번 읽으면 사라진다.
 * 그래서 기록기가 「기록해 두려고」 본문을 먼저 읽어 버리면,
 * <b>정작 앱에는 빈 본문이 도착해서 요청이 실패한다.</b>
 * 관측하려다 관측 대상을 망가뜨리는 것이고, 이 프로젝트가 절대 하면 안 되는 일이다.
 *
 * <h2>어떻게 푸나 — 먼저 읽지 않고, 앱이 읽을 때 «옆에서» 베낀다</h2>
 * <pre>
 *   앱 ──read()──▶ [이 껍데기] ──read()──▶ 진짜 스트림
 *                       │
 *                       └─ 읽힌 바이트를 복사본에 쌓는다 (상한까지만)
 * </pre>
 * 앱은 원래대로 전부 받고, 우리는 복사본을 본다. 🔴 앱이 받는 바이트는 하나도 안 바뀐다.
 *
 * <h2>⚠️ 남는 한계 두 가지 — 둘 다 「모름」으로 적는다</h2>
 * <ul>
 *   <li><b>앱이 본문을 안 읽으면 우리도 못 본다.</b> 그때 복사본은 비어 있는데,
 *       그건 「본문이 없었다」가 아니라 「우리가 못 봤다」다.
 *       {@link #bodyWasRead()} 가 그 둘을 갈라 준다</li>
 *   <li><b>상한을 넘으면 뒤가 잘린다.</b> 잘렸다는 사실을 {@link #truncated()} 로 남긴다.
 *       🔴 조용히 짧은 본문만 적으면 재생이 다른 요청을 보낸다</li>
 * </ul>
 */
public final class CachingRequest extends HttpServletRequestWrapper {

    private final int maxBytes;
    private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

    private boolean bodyWasRead;
    private boolean truncated;
    private long readBytes;

    private ServletInputStream wrapped;

    public CachingRequest(HttpServletRequest request, int maxBytes) {
        super(request);
        this.maxBytes = maxBytes;
    }

    /** 지금까지 베껴 둔 본문. 앱이 읽은 만큼만 들어 있다. */
    public byte[] capturedBody() {
        return copy.toByteArray();
    }

    /** 🔴 앱이 본문을 «읽기는 했나». 거짓이면 빈 복사본은 「없었다」가 아니라 「못 봤다」다. */
    public boolean bodyWasRead() {
        return bodyWasRead;
    }

    /** 상한을 넘겨 뒤가 잘렸나. */
    public boolean truncated() {
        return truncated;
    }

    /** 앱이 실제로 읽은 총 바이트. 잘렸어도 이 값은 진짜 크기를 말해 준다. */
    public long readBytes() {
        return readBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (wrapped == null) {
            wrapped = new CopyingStream(super.getInputStream());
        }
        return wrapped;
    }

    /**
     * 🔴 {@code getReader()} 도 반드시 덮어쓴다.
     *
     * <p>스프링 MVC 가 JSON 본문을 읽는 길은 앱마다 다르다 — 어떤 경로는 {@code getInputStream()},
     * 어떤 경로는 {@code getReader()} 로 간다. 한쪽만 감싸면 <b>어떤 앱에서는 본문이 통째로
     * 안 잡히고</b>, 그게 「본문 없는 요청」으로 기록된다.
     */
    @Override
    public java.io.BufferedReader getReader() throws IOException {
        String encoding = getCharacterEncoding();
        return new java.io.BufferedReader(new java.io.InputStreamReader(
                getInputStream(),
                encoding != null ? encoding : java.nio.charset.StandardCharsets.UTF_8.name()));
    }

    private final class CopyingStream extends ServletInputStream {

        private final ServletInputStream real;

        private CopyingStream(ServletInputStream real) {
            this.real = real;
        }

        @Override
        public int read() throws IOException {
            int b = real.read();
            if (b != -1) {
                note(b);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = real.read(buffer, offset, length);
            if (count > 0) {
                for (int i = 0; i < count; i++) {
                    note(buffer[offset + i] & 0xFF);
                }
            }
            return count;
        }

        private void note(int b) {
            bodyWasRead = true;
            readBytes++;
            if (copy.size() < maxBytes) {
                copy.write(b);
            } else {
                truncated = true;
            }
        }

        // ── 아래 넷은 그대로 넘긴다. 비동기 입출력의 동작을 바꾸면 안 된다 ──

        @Override
        public boolean isFinished() {
            return real.isFinished();
        }

        @Override
        public boolean isReady() {
            return real.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            real.setReadListener(listener);
        }

        @Override
        public int available() throws IOException {
            return real.available();
        }
    }
}
