package enkan.faas.azure;

import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentLifecycle;
import enkan.component.SystemComponent;
import enkan.faas.FaasAdapter;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

/**
 * Enkan component that activates a {@link FaasAdapter} for Azure Functions.
 *
 * <pre>{@code
 * private static final AzureHttpAdapter ADAPTER = new AzureHttpAdapter();
 * private static final EnkanSystem SYSTEM;
 *
 * static {
 *     SYSTEM = EnkanSystem.of(
 *         "app",      new ApplicationComponent<>(MyApplicationFactory.class.getName()),
 *         "function", new AzureFunctionsComponent(ADAPTER)
 *     );
 *     SYSTEM.start();
 * }
 * }</pre>
 */
public class AzureFunctionsComponent extends SystemComponent<AzureFunctionsComponent> {

    private final FaasAdapter<?, ?> adapter;

    public AzureFunctionsComponent(FaasAdapter<?, ?> adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter must not be null");
        this.adapter = adapter;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ComponentLifecycle<AzureFunctionsComponent> lifecycle() {
        return new ComponentLifecycle<>() {
            @Override
            public void start(AzureFunctionsComponent component) {
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
            public void stop(AzureFunctionsComponent component) {
                component.adapter.deactivate();
            }
        };
    }
}
