package io.reliablepush.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bounds every {@code /v1/**} request body to {@value #MAX_BODY_BYTES} bytes (spec 5, "Hygiene"). A declared
 * {@code Content-Length} above the limit is answered with 413 without reading anything; for chunked or otherwise
 * unsized bodies the input stream is wrapped and fails with {@link RequestBodyTooLargeException} as soon as more
 * than the limit has been read ({@link ProblemHandler} maps that to 413 as well).
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    public static final int MAX_BODY_BYTES = 64 * 1024;

    private static final String TITLE = "Payload Too Large";
    private static final String DETAIL = "The request body must not exceed " + MAX_BODY_BYTES + " bytes.";

    private final ProblemWriter problems;

    public BodySizeLimitFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !RequestPaths.isUnder(request, "/v1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(request, response);
            return;
        }
        try {
            chain.doFilter(new LimitedRequest(request), response);
        } catch (ServletException | IOException | RuntimeException e) {
            // normally ProblemHandler already answered; this covers a read failure outside of MVC (e.g. in a filter)
            if (RequestBodyTooLargeException.in(e) && !response.isCommitted()) {
                reject(request, response);
                return;
            }
            throw e;
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        // the unread body would be reused as the next request on a kept-alive connection: close it
        problems.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, TITLE, DETAIL, Map.of("Connection", "close"));
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private ServletInputStream stream;

        LimitedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new LimitedInputStream(super.getInputStream());
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private long count;

        LimitedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws IOException {
            count += n;
            if (count > MAX_BODY_BYTES) {
                throw new RequestBodyTooLargeException();
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
