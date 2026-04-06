package enkan.faas;

import enkan.component.ApplicationComponent;
import enkan.system.EnkanSystem;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FunctionInvokerTest {

    private FunctionInvoker<Map<String, Object>, FakeResponse> invoker;

    @AfterEach
    void tearDown() {
        if (invoker != null) {
            invoker.getSystem().stop();
        }
    }

    @Test
    void bootStartsTheSystemAndExposesAnInvoker() {
        invoker = boot();
        assertThat(invoker).isNotNull();
        assertThat(invoker.getSystem()).isNotNull();
    }

    @Test
    void invokeRoutesGetRequestThroughTheMiddlewareChain() {
        invoker = boot();
        Map<String, Object> event = Map.of("method", "GET", "uri", "/anything");

        FakeResponse res = invoker.invoke(event);

        assertThat(res.status).isEqualTo(200);
        assertThat(res.body).isEqualTo("ok");
    }

    @Test
    void invokeRoutesPostRequestThroughTheMiddlewareChain() {
        invoker = boot();
        Map<String, Object> event = Map.of("method", "POST", "uri", "/anything");

        FakeResponse res = invoker.invoke(event);

        assertThat(res.status).isEqualTo(201);
        assertThat(res.body).isEqualTo("created");
    }

    @Test
    void invokeReturnsInternalServerErrorWhenMiddlewareThrows() {
        invoker = boot();
        Map<String, Object> event = Map.of("method", "GET", "uri", "/boom");

        FakeResponse res = invoker.invoke(event);

        assertThat(res.status).isEqualTo(500);
        assertThat(res.body).contains("Internal Server Error");
    }

    @Test
    void bootFailsClearlyWhenApplicationComponentNameIsWrong() {
        try {
            FunctionInvoker.boot(
                    FunctionInvokerTest::buildSystem,
                    "missing",
                    new FakeRequestAdapter(),
                    new FakeResponseAdapter());
            org.junit.jupiter.api.Assertions.fail("expected IllegalStateException");
        } catch (Exception expected) {
            // EnkanSystem.getComponent throws if the name is unknown — accept either
            // a wrapped or direct error so the test does not couple to that detail.
            assertThat(expected).isNotNull();
        }
    }

    private static FunctionInvoker<Map<String, Object>, FakeResponse> boot() {
        return FunctionInvoker.boot(
                FunctionInvokerTest::buildSystem,
                "app",
                new FakeRequestAdapter(),
                new FakeResponseAdapter());
    }

    private static EnkanSystem buildSystem() {
        return EnkanSystem.of(
                "app", new ApplicationComponent<HttpRequest, HttpResponse>(
                        TestApplicationFactory.class.getName())
        );
    }

    /** A trivial event type for tests — Map of method/uri/body. */
    private static final class FakeRequestAdapter
            implements RequestAdapter<Map<String, Object>> {
        @Override
        public HttpRequest toHttpRequest(Map<String, Object> event,
                                         java.util.function.Supplier<HttpRequest> requestFactory) {
            HttpRequest req = requestFactory.get();
            req.setRequestMethod((String) event.getOrDefault("method", "GET"));
            req.setUri((String) event.getOrDefault("uri", "/"));
            req.setScheme("https");
            req.setServerName("test.local");
            req.setServerPort(443);
            req.setRemoteAddr("127.0.0.1");
            req.setHeaders(enkan.web.collection.Headers.empty());
            String body = (String) event.getOrDefault("body", "");
            req.setBody(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            return req;
        }
    }

    /** A trivial response wrapper that captures the response that came back. */
    private static final class FakeResponse {
        final int status;
        final String body;
        final Map<String, String> headers;

        FakeResponse(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    private static final class FakeResponseAdapter implements ResponseAdapter<FakeResponse> {
        @Override
        public FakeResponse toResponse(HttpResponse response) {
            Map<String, String> headers = new HashMap<>();
            response.getHeaders().keySet().forEach(k ->
                    headers.put(k, String.valueOf(response.getHeaders().get(k))));
            String body = response.getBodyAsString();
            return new FakeResponse(response.getStatus(), body, headers);
        }
    }
}
