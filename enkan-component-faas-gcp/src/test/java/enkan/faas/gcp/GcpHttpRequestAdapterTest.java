package enkan.faas.gcp;

import com.google.cloud.functions.HttpRequest;
import enkan.web.collection.Headers;
import enkan.web.data.DefaultHttpRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class GcpHttpRequestAdapterTest {

    private final GcpHttpAdapter adapter = new GcpHttpAdapter();
    private final Supplier<enkan.web.data.HttpRequest> factory = DefaultHttpRequest::new;

    @Test
    void mapsMethodPathAndHeaders() throws IOException {
        FakeHttpRequest event = new FakeHttpRequest();
        event.method = "GET";
        event.path = "/todos";
        event.uri = "https://api.example.com/todos?limit=10";
        event.query = Optional.of("limit=10");
        event.headers.put("Content-Type", List.of("application/json"));
        event.headers.put("X-Custom", List.of("abc"));

        enkan.web.data.HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getRequestMethod()).isEqualTo("GET");
        assertThat(req.getUri()).isEqualTo("/todos");
        assertThat(req.getQueryString()).isEqualTo("limit=10");
        assertThat(req.getScheme()).isEqualTo("https");
        assertThat(req.getServerName()).isEqualTo("api.example.com");
        assertThat(req.getServerPort()).isEqualTo(443);

        Headers h = req.getHeaders();
        assertThat(h.get("Content-Type")).isEqualTo("application/json");
        assertThat(h.get("X-Custom")).isEqualTo("abc");
    }

    @Test
    void passesThroughInputStreamBody() throws IOException {
        FakeHttpRequest event = new FakeHttpRequest();
        event.method = "POST";
        event.path = "/todos";
        event.body = new ByteArrayInputStream("hello".getBytes());

        enkan.web.data.HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(new String(req.getBody().readAllBytes())).isEqualTo("hello");
    }

    @Test
    void nullBodyBecomesEmptyStream() throws IOException {
        FakeHttpRequest event = new FakeHttpRequest();
        event.method = "GET";
        event.path = "/";

        enkan.web.data.HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getBody().readAllBytes()).isEmpty();
    }

    /** Minimal {@link HttpRequest} stub for adapter tests. */
    static class FakeHttpRequest implements HttpRequest {
        String method = "GET";
        String uri;
        String path = "/";
        Optional<String> query = Optional.empty();
        final Map<String, List<String>> headers = new HashMap<>();
        InputStream body;

        @Override public String getMethod() { return method; }
        @Override public String getUri() { return uri; }
        @Override public String getPath() { return path; }
        @Override public Optional<String> getQuery() { return query; }
        @Override public Map<String, List<String>> getQueryParameters() { return Map.of(); }
        @Override public Map<String, HttpPart> getParts() { return Map.of(); }
        @Override public Optional<String> getContentType() { return Optional.empty(); }
        @Override public long getContentLength() { return -1; }
        @Override public Optional<String> getCharacterEncoding() { return Optional.empty(); }
        @Override public InputStream getInputStream() { return body != null ? body : InputStream.nullInputStream(); }
        @Override public java.io.BufferedReader getReader() { return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream())); }
        @Override public Map<String, List<String>> getHeaders() { return headers; }
    }
}
