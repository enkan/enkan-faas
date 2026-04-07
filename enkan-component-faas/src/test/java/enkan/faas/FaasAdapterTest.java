package enkan.faas;

import enkan.component.ApplicationComponent;
import enkan.component.ComponentRelationship;
import enkan.system.EnkanSystem;
import enkan.web.collection.Headers;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link FaasAdapter} + component activation lifecycle.
 */
class FaasAdapterTest {

    private EnkanSystem system;

    @AfterEach
    void tearDown() {
        if (system != null) system.stop();
    }

    @Test
    void activationWiresApplicationToAdapter() {
        FakeAdapter adapter = new FakeAdapter();
        system = buildSystem(adapter);
        system.start();

        FakeResponse res = adapter.invoke(Map.of("method", "GET", "uri", "/anything"));

        assertThat(res.status).isEqualTo(200);
        assertThat(res.body).isEqualTo("ok");
    }

    @Test
    void invokeReturns201ForPost() {
        FakeAdapter adapter = new FakeAdapter();
        system = buildSystem(adapter);
        system.start();

        FakeResponse res = adapter.invoke(Map.of("method", "POST", "uri", "/anything"));

        assertThat(res.status).isEqualTo(201);
        assertThat(res.body).isEqualTo("created");
    }

    @Test
    void invokeReturns500WhenMiddlewareThrows() {
        FakeAdapter adapter = new FakeAdapter();
        system = buildSystem(adapter);
        system.start();

        FakeResponse res = adapter.invoke(Map.of("method", "GET", "uri", "/boom"));

        assertThat(res.status).isEqualTo(500);
        assertThat(res.body).contains("Internal Server Error");
    }

    @Test
    void invokeBeforeActivationThrows() {
        FakeAdapter adapter = new FakeAdapter();
        // system not started — adapter not activated
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> adapter.invoke(Map.of("method", "GET", "uri", "/"))
        )).isNotNull();
    }

    @Test
    void deactivationClearsAdapter() {
        FakeAdapter adapter = new FakeAdapter();
        system = buildSystem(adapter);
        system.start();
        system.stop();
        system = null;

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> adapter.invoke(Map.of("method", "GET", "uri", "/"))
        )).isNotNull();
    }

    private static EnkanSystem buildSystem(FakeAdapter adapter) {
        return EnkanSystem.of(
                "app",  new ApplicationComponent<HttpRequest, HttpResponse>(
                                TestApplicationFactory.class.getName()),
                "faas", new FakeComponent(adapter))
            .relationships(
                ComponentRelationship.component("faas").using("app"));
    }

    // -----------------------------------------------------------------------
    // Test doubles
    // -----------------------------------------------------------------------

    /** Minimal component that activates a FakeAdapter. */
    private static final class FakeComponent
            extends enkan.component.SystemComponent<FakeComponent> {

        private final FakeAdapter adapter;

        FakeComponent(FakeAdapter adapter) { this.adapter = adapter; }

        @Override
        protected enkan.component.ComponentLifecycle<FakeComponent> lifecycle() {
            return new enkan.component.ComponentLifecycle<>() {
                @Override
                public void start(FakeComponent c) {
                    @SuppressWarnings("unchecked")
                    ApplicationComponent<HttpRequest, HttpResponse> appComp =
                            c.getDependency(ApplicationComponent.class);
                    enkan.Application<HttpRequest, HttpResponse> app = appComp.getApplication();
                    enkan.web.application.WebApplication webApp =
                            (enkan.web.application.WebApplication) app;
                    c.adapter.activate(app, webApp::createRequest);
                }
                @Override
                public void stop(FakeComponent c) { c.adapter.deactivate(); }
            };
        }
    }

    private static final class FakeAdapter extends FaasAdapter<Map<String, Object>, FakeResponse> {
        @Override
        protected HttpRequest toHttpRequest(Map<String, Object> event, Supplier<HttpRequest> rf) {
            HttpRequest req = rf.get();
            req.setRequestMethod((String) event.getOrDefault("method", "GET"));
            req.setUri((String) event.getOrDefault("uri", "/"));
            req.setScheme("https");
            req.setServerName("test.local");
            req.setServerPort(443);
            req.setRemoteAddr("127.0.0.1");
            req.setHeaders(Headers.empty());
            req.setBody(new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8)));
            return req;
        }

        @Override
        protected FakeResponse toResponse(HttpResponse response) {
            Map<String, String> headers = new HashMap<>();
            if (response.getHeaders() != null) {
                response.getHeaders().keySet().forEach(k ->
                        headers.put(k, String.valueOf(response.getHeaders().get(k))));
            }
            return new FakeResponse(response.getStatus(), response.getBodyAsString(), headers);
        }

        // Expose invoke() publicly for tests
        @Override
        public FakeResponse invoke(Map<String, Object> event) {
            return super.invoke(event);
        }
    }

    private record FakeResponse(int status, String body, Map<String, String> headers) {}
}
