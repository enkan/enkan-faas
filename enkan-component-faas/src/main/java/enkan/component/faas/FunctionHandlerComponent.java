package enkan.component.faas;

import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentLifecycle;
import enkan.component.SystemComponent;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.util.function.Supplier;

/**
 * Optional {@link SystemComponent} wrapper that exposes a FaaS-ready view of
 * the underlying {@link ApplicationComponent}. Useful for handlers that
 * prefer {@code system.getComponent(...)} over {@code static final} field
 * initialization — e.g. when the handler wants to be unit-tested with a
 * custom {@code EnkanSystem}.
 *
 * <p>Not required by any cloud adapter. {@link enkan.faas.FunctionInvoker}
 * works directly on {@code ApplicationComponent} without this component.
 * Provided purely for ergonomic parity with other Enkan components.
 *
 * <p>Usage:
 * <pre>{@code
 * EnkanSystem.of(
 *     "app",     new ApplicationComponent<>("com.example.MyAppFactory"),
 *     "handler", new FunctionHandlerComponent()
 * ).relationships(
 *     component("handler").using("app")
 * );
 * }</pre>
 */
public class FunctionHandlerComponent
        extends SystemComponent<FunctionHandlerComponent> {

    private Application<HttpRequest, HttpResponse> application;
    private Supplier<HttpRequest> requestFactory;

    @Override
    protected ComponentLifecycle<FunctionHandlerComponent> lifecycle() {
        return new ComponentLifecycle<>() {
            @Override
            @SuppressWarnings("unchecked")
            public void start(FunctionHandlerComponent component) {
                ApplicationComponent<HttpRequest, HttpResponse> appComponent =
                        component.getDependency(ApplicationComponent.class);
                if (appComponent == null) {
                    throw new IllegalStateException(
                            "FunctionHandlerComponent requires a dependency on ApplicationComponent");
                }
                component.application = appComponent.getApplication();
                if (!(component.application instanceof WebApplication webApp)) {
                    throw new IllegalStateException(
                            "FunctionHandlerComponent expects WebApplication, got "
                                    + component.application);
                }
                component.requestFactory = webApp::createRequest;
            }

            @Override
            public void stop(FunctionHandlerComponent component) {
                component.application = null;
                component.requestFactory = null;
            }
        };
    }

    public Application<HttpRequest, HttpResponse> getApplication() {
        return application;
    }

    public Supplier<HttpRequest> getRequestFactory() {
        return requestFactory;
    }
}
