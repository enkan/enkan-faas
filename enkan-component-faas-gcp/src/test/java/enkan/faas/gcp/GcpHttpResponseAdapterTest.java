package enkan.faas.gcp;

import com.google.cloud.functions.HttpResponse;
import enkan.web.collection.Headers;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GcpHttpResponseAdapterTest {

    private final GcpHttpAdapter adapter = new GcpHttpAdapter();

    @Test
    void writesStatusHeadersAndStringBody() throws IOException {
        enkan.web.data.HttpResponse src = enkan.web.data.HttpResponse.of("hello");
        src.setStatus(200);
        src.setContentType("text/plain");
        FakeHttpResponse sink = new FakeHttpResponse();

        adapter.writeTo(src, sink);

        assertThat(sink.status).isEqualTo(200);
        assertThat(sink.output.toString(StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(sink.headers.get("Content-Type")).contains("text/plain");
    }

    @Test
    void binaryInputStreamBodyIsStreamedVerbatim() throws IOException {
        byte[] payload = {0x01, 0x02, 0x03, (byte) 0xff};
        enkan.web.data.HttpResponse src = enkan.web.data.HttpResponse.of(new ByteArrayInputStream(payload));
        src.setStatus(200);
        FakeHttpResponse sink = new FakeHttpResponse();

        adapter.writeTo(src, sink);

        assertThat(sink.output.toByteArray()).containsExactly(payload);
    }

    @Test
    void multipleSetCookieHeadersAreEmittedViaAppendHeader() throws IOException {
        enkan.web.data.HttpResponse src = enkan.web.data.HttpResponse.of("");
        src.setStatus(204);
        Headers headers = Headers.empty();
        headers.put("Set-Cookie", "a=1");
        headers.put("Set-Cookie", "b=2");
        src.setHeaders(headers);
        FakeHttpResponse sink = new FakeHttpResponse();

        adapter.writeTo(src, sink);

        assertThat(sink.headers.get("Set-Cookie")).containsExactly("a=1", "b=2");
    }

    /** Minimal {@link HttpResponse} stub for adapter tests. */
    static class FakeHttpResponse implements HttpResponse {
        int status;
        String statusMessage;
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override public void setStatusCode(int code) { this.status = code; }
        @Override public void setStatusCode(int code, String message) {
            this.status = code;
            this.statusMessage = message;
        }
        @Override public void setContentType(String type) {
            headers.computeIfAbsent("Content-Type", k -> new ArrayList<>()).clear();
            headers.computeIfAbsent("Content-Type", k -> new ArrayList<>()).add(type);
        }
        @Override public Optional<String> getContentType() {
            List<String> v = headers.get("Content-Type");
            return v == null || v.isEmpty() ? Optional.empty() : Optional.of(v.get(0));
        }
        @Override public void appendHeader(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        @Override public Map<String, List<String>> getHeaders() { return headers; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public BufferedWriter getWriter() {
            return new BufferedWriter(new java.io.OutputStreamWriter(output));
        }
    }
}
