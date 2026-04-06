package enkan.faas.azure;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import enkan.web.data.DefaultHttpRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class AzureHttpRequestAdapterTest {

    private final AzureHttpRequestAdapter adapter = new AzureHttpRequestAdapter();
    private final Supplier<enkan.web.data.HttpRequest> factory = DefaultHttpRequest::new;

    @Test
    void mapsMethodUriAndHeaders() {
        FakeRequest req = new FakeRequest();
        req.method = HttpMethod.GET;
        req.uri = URI.create("https://todo.azurewebsites.net/todos?limit=10");
        req.headers.put("Content-Type", "application/json");
        req.headers.put("X-Custom", "abc");

        enkan.web.data.HttpRequest enkan = adapter.toHttpRequest(req, factory);

        assertThat(enkan.getRequestMethod()).isEqualTo("GET");
        assertThat(enkan.getUri()).isEqualTo("/todos");
        assertThat(enkan.getQueryString()).isEqualTo("limit=10");
        assertThat(enkan.getScheme()).isEqualTo("https");
        assertThat(enkan.getServerName()).isEqualTo("todo.azurewebsites.net");
        assertThat(enkan.getHeaders().get("Content-Type")).isEqualTo("application/json");
        assertThat(enkan.getHeaders().get("X-Custom")).isEqualTo("abc");
    }

    @Test
    void passesBodyThroughAsInputStream() throws IOException {
        FakeRequest req = new FakeRequest();
        req.method = HttpMethod.POST;
        req.uri = URI.create("https://todo.azurewebsites.net/todos");
        req.body = Optional.of("hello");

        enkan.web.data.HttpRequest enkan = adapter.toHttpRequest(req, factory);

        assertThat(new String(enkan.getBody().readAllBytes())).isEqualTo("hello");
    }

    @Test
    void emptyBodyProducesEmptyStream() throws IOException {
        FakeRequest req = new FakeRequest();
        req.method = HttpMethod.GET;
        req.uri = URI.create("https://todo.azurewebsites.net/todos");
        req.body = Optional.empty();

        enkan.web.data.HttpRequest enkan = adapter.toHttpRequest(req, factory);

        assertThat(enkan.getBody().readAllBytes()).isEmpty();
    }

    /** Minimal Azure {@link HttpRequestMessage} stub for adapter tests. */
    static class FakeRequest implements HttpRequestMessage<Optional<String>> {
        HttpMethod method = HttpMethod.GET;
        URI uri;
        final Map<String, String> headers = new HashMap<>();
        Optional<String> body = Optional.empty();

        @Override public URI getUri() { return uri; }
        @Override public HttpMethod getHttpMethod() { return method; }
        @Override public Map<String, String> getHeaders() { return headers; }
        @Override public Map<String, String> getQueryParameters() { return Map.of(); }
        @Override public Optional<String> getBody() { return body; }

        @Override
        public HttpResponseMessage.Builder createResponseBuilder(HttpStatus status) {
            throw new UnsupportedOperationException("not used in request adapter tests");
        }
        @Override
        public HttpResponseMessage.Builder createResponseBuilder(HttpStatusType status) {
            throw new UnsupportedOperationException("not used in request adapter tests");
        }
    }
}
