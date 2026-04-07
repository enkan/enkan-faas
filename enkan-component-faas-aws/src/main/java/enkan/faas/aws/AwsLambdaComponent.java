package enkan.faas.aws;

import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentLifecycle;
import enkan.component.SystemComponent;
import enkan.faas.FaasAdapter;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

/**
 * Enkan component that activates a {@link FaasAdapter} for AWS Lambda.
 *
 * <p>Mirrors the role of {@code JettyComponent}: it depends on
 * {@link ApplicationComponent}, retrieves the live {@link Application} at
 * {@code start()}, injects it into the adapter, and registers CRaC for
 * AWS SnapStart support.
 *
 * <p>Typical usage inside a Lambda handler's static initializer:
 *
 * <pre>{@code
 * private static final ApiGatewayV2Adapter ADAPTER = new ApiGatewayV2Adapter();
 * private static final EnkanSystem SYSTEM;
 *
 * static {
 *     SYSTEM = EnkanSystem.of(
 *         "app",    new ApplicationComponent<>(MyApplicationFactory.class.getName()),
 *         "lambda", new AwsLambdaComponent(ADAPTER)
 *     );
 *     SYSTEM.start();
 *     SYSTEM.registerCrac();   // no-op on non-CRaC JVMs; enables SnapStart
 * }
 * }</pre>
 */
public class AwsLambdaComponent extends SystemComponent<AwsLambdaComponent> {

    private final FaasAdapter<?, ?> adapter;

    public AwsLambdaComponent(FaasAdapter<?, ?> adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter must not be null");
        this.adapter = adapter;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected ComponentLifecycle<AwsLambdaComponent> lifecycle() {
        return new ComponentLifecycle<>() {
            @Override
            public void start(AwsLambdaComponent component) {
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
            public void stop(AwsLambdaComponent component) {
                component.adapter.deactivate();
            }
        };
    }
}
