package io.hindsight.recorder.http;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 응답 본문을 나가는 길에 베껴 두는 껍데기.
 *
 * <h2>🔴 응답 본문을 반드시 남기는 이유</h2>
 * 상태 코드만 검사하는 테스트는 <b>예외를 삼키는 처리기 하나로 통과시킬 수 있다.</b>
 * LLM 이 「고쳤다」면서 예외를 잡아 200 을 돌려주는 코드를 내놓으면, 상태 코드만 보는
 * 채점기는 그걸 통과시킨다. 본문이 있어야 「값이 맞나」를 물을 수 있다.
 *
 * <h2>🔴 여기서 가장 흔한 사고 — 베껴만 두고 안 돌려보내면 응답이 통째로 사라진다</h2>
 * 우리가 가로챈 바이트는 아직 클라이언트에게 안 갔다. 다 끝난 뒤
 * {@link #copyToRealResponse()} 를 <b>반드시</b> 불러야 나간다. 안 부르면 브라우저가
 * <b>빈 응답</b>을 받는다 — 관측 도구가 앱의 모든 응답을 지우는 셈이다.
 * {@code finally} 안에 둔다.
 */
public final class CachingResponse extends HttpServletResponseWrapper {

    private final int maxBytes;
    private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

    private CopyingStream stream;
    private PrintWriter writer;
    private boolean truncated;
    private long writtenBytes;

    public CachingResponse(HttpServletResponse response, int maxBytes) {
        super(response);
        this.maxBytes = maxBytes;
    }

    public byte[] capturedBody() {
        return copy.toByteArray();
    }

    public boolean truncated() {
        return truncated;
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() 를 이미 불렀다");
        }
        if (stream == null) {
            stream = new CopyingStream(super.getOutputStream());
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            String encoding = getCharacterEncoding();
            writer = new PrintWriter(new java.io.OutputStreamWriter(
                    getOutputStream(),
                    encoding != null ? encoding : java.nio.charset.StandardCharsets.UTF_8.name()), true);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    /**
     * 🔴 베껴 둔 것을 진짜 응답으로 내보낸다. 안 부르면 클라이언트가 빈 응답을 받는다.
     *
     * <p>이 껍데기는 «지나가면서» 베끼므로 바이트는 이미 아래로 내려갔다. 여기서는
     * 남은 버퍼만 밀어낸다. 그래도 이 메서드를 따로 두는 이유는, 나중에 버퍼링 방식으로
     * 바뀌더라도 <b>부르는 자리를 옮기지 않아도 되게</b> 하기 위해서다.
     */
    public void copyToRealResponse() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (stream != null) {
            stream.flush();
        }
    }

    private final class CopyingStream extends ServletOutputStream {

        private final ServletOutputStream real;

        private CopyingStream(ServletOutputStream real) {
            this.real = real;
        }

        @Override
        public void write(int b) throws IOException {
            // 🔴 진짜 스트림에 «먼저» 쓴다. 우리 복사가 실패해도 앱의 응답은 나가야 한다.
            real.write(b);
            note(b);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            real.write(buffer, offset, length);
            for (int i = 0; i < length; i++) {
                note(buffer[offset + i] & 0xFF);
            }
        }

        private void note(int b) {
            writtenBytes++;
            if (copy.size() < maxBytes) {
                copy.write(b);
            } else {
                truncated = true;
            }
        }

        @Override
        public void flush() throws IOException {
            real.flush();
        }

        @Override
        public boolean isReady() {
            return real.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            real.setWriteListener(listener);
        }
    }
}
