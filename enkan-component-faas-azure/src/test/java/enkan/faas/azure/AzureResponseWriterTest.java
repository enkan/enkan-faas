package enkan.faas.azure;

import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentRelationship;
import enkan.faas.FaasAdapter;
import enkan.system.EnkanSystem;
import enkan.web.collection.Headers;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for response building in {@link AzureHttpAdapter}.
 */
class AzureResponseWriterTest {

    private static AzureHttpAdapter adapter;
    private static EnkanSystem system;

    @BeforeAll
    static void bootSystem() {
        adapter = new AzureHttpAdapter();
        system = EnkanSystem.of(
                        "app",   new ApplicationComponent<HttpRequest, HttpResponse>(
                                AzureTestApplicationFactory.class.getName()),
                        "azure", new AzureFunctionsComponent(adapter))
                .relationships(ComponentRelationship.component("azure").using("app"));
        system.start();
    }

    @AfterAll
    static void stopSystem() {
        if (system != null) system.stop();
    }

    @Test
    void writesStatusHeadersAndStringBody() {
        FakeRequest req = new FakeRequest("GET", "/hello");

        HttpResponseMessage out = adapter.handleRequest(req);

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).isEqualTo("hello");
        assertThat(out.getHeader("Content-Type")).isEqualTo("text/plain");
    }

    @Test
    void mapsCustomStatusCodes() {
        FakeRequest req = new FakeRequest("GET", "/teapot");

        HttpResponseMessage out = adapter.handleRequest(req);

        assertThat(out.getStatusCode()).isEqualTo(418);
    }

    @Test
    void copiesMultipleHeaders() {
        FakeRequest req = new FakeRequest("GET", "/headers");

        HttpResponseMessage out = adapter.handleRequest(req);

        assertThat(out.getStatusCode()).isEqualTo(204);
        assertThat(out.getHeader("X-Request-Id")).isEqualTo("abc");
        assertThat(out.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    static class FakeRequest implements HttpRequestMessage<Optional<String>> {
        private final String method;
        private final String path;

        FakeRequest(String method, String path) {
            this.method = method;
            this.path = path;
        }

        @Override public URI getUri() { return URI.create("https://az.local" + path); }
        @Override public HttpMethod getHttpMethod() { return HttpMethod.value(method); }
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
            this.status = status; return this;
        }
        @Override public HttpResponseMessage.Builder header(String name, String value) {
            headers.put(name, value); return this;
        }
        @Override public HttpResponseMessage.Builder body(Object body) {
            this.body = body; return this;
        }
        @Override public HttpResponseMessage build() {
            HttpStatusType s = status;
            Map<String, String> h = new HashMap<>(headers);
            Object b = body;
            return new HttpResponseMessage() {
                @Override public HttpStatusType getStatus() { return s; }
                @Override public int getStatusCode() { return s.value(); }
                @Override public String getHeader(String key) { return h.get(key); }
                @Override public Object getBody() { return b; }
            };
        }
    }
}
