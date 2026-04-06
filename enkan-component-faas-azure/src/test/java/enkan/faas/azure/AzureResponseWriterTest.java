package enkan.faas.azure;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import enkan.web.collection.Headers;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AzureResponseWriterTest {

    @Test
    void writesStatusHeadersAndStringBody() {
        enkan.web.data.HttpResponse src = enkan.web.data.HttpResponse.of("hello");
        src.setStatus(200);
        src.setContentType("text/plain");
        FakeRequest req = new FakeRequest();

        HttpResponseMessage out = AzureResponseWriter.write(req, src);

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).isEqualTo("hello");
        assertThat(out.getHeader("Content-Type")).isEqualTo("text/plain");
    }

    @Test
    void mapsCustomStatusCodes() {
        enkan.web.data.HttpResponse src = enkan.web.data.HttpResponse.of("teapot");
        src.setStatus(418);
        FakeRequest req = new FakeRequest();

        HttpResponseMessage out = AzureResponseWriter.write(req, src);

        assertThat(out.getStatusCode()).isEqualTo(418);
    }

    @Test
    void copiesMultipleHeaders() {
        enkan.web.data.HttpResponse src = enkan.web.data.HttpResponse.of("");
        src.setStatus(204);
        Headers h = Headers.empty();
        h.put("X-Request-Id", "abc");
        h.put("Cache-Control", "no-store");
        src.setHeaders(h);
        FakeRequest req = new FakeRequest();

        HttpResponseMessage out = AzureResponseWriter.write(req, src);

        assertThat(out.getStatusCode()).isEqualTo(204);
        assertThat(out.getHeader("X-Request-Id")).isEqualTo("abc");
        assertThat(out.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    /** Minimal Azure {@link HttpRequestMessage} with a working builder. */
    static class FakeRequest implements HttpRequestMessage<Optional<String>> {
        @Override public URI getUri() { return URI.create("https://az.local/"); }
        @Override public HttpMethod getHttpMethod() { return HttpMethod.GET; }
        @Override public Map<String, String> getHeaders() { return Map.of(); }
        @Override public Map<String, String> getQueryParameters() { return Map.of(); }
        @Override public Optional<String> getBody() { return Optional.empty(); }

        @Override
        public HttpResponseMessage.Builder createResponseBuilder(HttpStatus status) {
            return new FakeBuilder(status);
        }
        @Override
        public HttpResponseMessage.Builder createResponseBuilder(HttpStatusType status) {
            return new FakeBuilder(status);
        }
    }

    static class FakeBuilder implements HttpResponseMessage.Builder {
        private HttpStatusType status;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private Object body;

        FakeBuilder(HttpStatusType status) { this.status = status; }

        @Override public HttpResponseMessage.Builder status(HttpStatusType status) {
            this.status = status;
            return this;
        }
        @Override public HttpResponseMessage.Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }
        @Override public HttpResponseMessage.Builder body(Object body) {
            this.body = body;
            return this;
        }
        @Override public HttpResponseMessage build() {
            HttpStatusType capturedStatus = status;
            Map<String, String> capturedHeaders = new HashMap<>(headers);
            Object capturedBody = body;
            return new HttpResponseMessage() {
                @Override public HttpStatusType getStatus() { return capturedStatus; }
                @Override public int getStatusCode() { return capturedStatus.value(); }
                @Override public String getHeader(String key) { return capturedHeaders.get(key); }
                @Override public Object getBody() { return capturedBody; }
            };
        }
    }
}
