package enkan.faas.gcp;

import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentLifecycle;
import enkan.component.SystemComponent;
import enkan.faas.StreamingFaasAdapter;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

/**
 * Enkan component that activates a {@link StreamingFaasAdapter} for
 * Google Cloud Functions / Cloud Run.
 *
 * <p>GCP's {@code HttpFunction.service()} writes directly into an output-stream
 * sink and returns {@code void}, so the streaming variant of the adapter is used
 * rather than the value-returning {@link enkan.faas.FaasAdapter}.
 *
 * <pre>{@code
 * private static final GcpHttpAdapter ADAPTER = new GcpHttpAdapter();
 * private static final EnkanSystem SYSTEM;
 *
 * static {
 *     SYSTEM = EnkanSystem.of(
 *         "app",      new ApplicationComponent<>(MyApplicationFactory.class.getName()),
 *         "function", new GcpFunctionsComponent(ADAPTER)
 *     );
 *     SYSTEM.start();
 * }
 * }</pre>
 */
public class GcpFunctionsComponent extends SystemComponent<GcpFunctionsComponent> {

    private final StreamingFaasAdapter<?, ?> adapter;

    public GcpFunctionsComponent(StreamingFaasAdapter<?, ?> adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter must not be null");
        this.adapter = adapter;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ComponentLifecycle<GcpFunctionsComponent> lifecycle() {
        return new ComponentLifecycle<>() {
            @Override
            public void start(GcpFunctionsComponent component) {
                ApplicationComponent<HttpRequest, HttpResponse> appComponent =
                        component.getDependency(ApplicationComponent.class);
                Application<HttpRequest, HttpResponse> app = appComponent.getApplication();
                if (!(app instanceof WebApplication webApp)) {
                    throw new IllegalStateException(
                            "Application is not a WebApplication: " + app);
                }
                component.adapter.activate(app, webApp::createRequest);
            }

            @Override
            public void stop(GcpFunctionsComponent component) {
                component.adapter.deactivate();
            }
        };
    }
}
