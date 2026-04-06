package enkan.faas;

import enkan.component.ApplicationComponent;
import enkan.system.EnkanSystem;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingFunctionInvokerTest {

    private StreamingFunctionInvoker<Map<String, Object>, FakeSink> invoker;

    @AfterEach
    void tearDown() {
        if (invoker != null) {
            invoker.getSystem().stop();
        }
    }

    @Test
    void invokeWritesResponseBodyIntoSink() throws Exception {
        invoker = boot();
        FakeSink sink = new FakeSink();

        invoker.invoke(Map.of("method", "GET", "uri", "/anything"), sink);

        assertThat(sink.status).isEqualTo(200);
        assertThat(sink.output.toString(StandardCharsets.UTF_8)).isEqualTo("ok");
    }

    @Test
    void invokeWritesErrorResponseWhenMiddlewareThrows() throws Exception {
        invoker = boot();
        FakeSink sink = new FakeSink();

        invoker.invoke(Map.of("method", "GET", "uri", "/boom"), sink);

        assertThat(sink.status).isEqualTo(500);
        assertThat(sink.output.toString(StandardCharsets.UTF_8)).contains("Internal Server Error");
    }

    private static StreamingFunctionInvoker<Map<String, Object>, FakeSink> boot() {
        return StreamingFunctionInvoker.boot(
                StreamingFunctionInvokerTest::buildSystem,
                "app",
                new FakeRequestAdapter(),
                new FakeStreamingAdapter());
    }

    private static EnkanSystem buildSystem() {
        return EnkanSystem.of(
                "app", new ApplicationComponent<HttpRequest, HttpResponse>(
                        TestApplicationFactory.class.getName())
        );
    }

    /** Reused from FunctionInvokerTest but copied here to keep the tests independent. */
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
            req.setHeaders(enkan.web.collection.Headers.empty());
            req.setBody(new ByteArrayInputStream(new byte[0]));
            return req;
        }
    }

    private static final class FakeSink {
        int status;
        final Map<String, String> headers = new HashMap<>();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
    }

    private static final class FakeStreamingAdapter
            implements StreamingResponseAdapter<FakeSink> {
        @Override
        public void writeTo(HttpResponse response, FakeSink sink) {
            sink.status = response.getStatus();
            if (response.getHeaders() != null) {
                response.getHeaders().forEachHeader((name, value) ->
                        sink.headers.put(name, value != null ? value.toString() : ""));
            }
            Object body = response.getBody();
            try {
                if (body instanceof String s) {
                    sink.output.write(s.getBytes(StandardCharsets.UTF_8));
                } else if (body instanceof java.io.InputStream in) {
                    in.transferTo(sink.output);
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
