package enkan.faas;

import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.system.EnkanSystem;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges a vendor FaaS event into an Enkan {@code Application} and back.
 *
 * <p>The typical handler usage assigns the invoker to a {@code static final}
 * field so that {@link #boot} runs in the platform's init phase (before
 * SnapStart checkpoints, before Lambda's first measured invocation):
 *
 * <pre>{@code
 * public class AwsLambdaHandler implements RequestHandler<E, R> {
 *     private static final FunctionInvoker<E, R> INVOKER =
 *         FunctionInvoker.boot(MyAppSystem::create, "app",
 *                              new ApiGatewayV2RequestAdapter(),
 *                              new ApiGatewayV2ResponseAdapter());
 *
 *     public R handleRequest(E event, Context ctx) {
 *         return INVOKER.invoke(event);
 *     }
 * }
 * }</pre>
 *
 * <p>{@link #boot} unconditionally calls {@link EnkanSystem#registerCrac()},
 * which is a documented no-op on non-CRaC JVMs (the {@code org.crac}
 * portability shim absorbs the call). The same handler code therefore works on
 * AWS SnapStart and on plain Lambda runtimes without any conditional logic.
 *
 * @param <E> the vendor event type
 * @param <R> the vendor response type
 */
public final class FunctionInvoker<E, R> {
    private static final Logger LOG = Logger.getLogger(FunctionInvoker.class.getName());

    private final EnkanSystem system;
    private final Application<HttpRequest, HttpResponse> application;
    private final Supplier<HttpRequest> requestFactory;
    private final RequestAdapter<E> requestAdapter;
    private final ResponseAdapter<R> responseAdapter;

    private FunctionInvoker(EnkanSystem system,
                            Application<HttpRequest, HttpResponse> application,
                            Supplier<HttpRequest> requestFactory,
                            RequestAdapter<E> requestAdapter,
                            ResponseAdapter<R> responseAdapter) {
        this.system = system;
        this.application = application;
        this.requestFactory = requestFactory;
        this.requestAdapter = requestAdapter;
        this.responseAdapter = responseAdapter;
    }

    /**
     * Boots an {@link EnkanSystem}, locates the named {@link ApplicationComponent},
     * registers CRaC, and returns an invoker ready for per-event use.
     *
     * @param systemFactory             builds a fresh {@code EnkanSystem}
     * @param applicationComponentName  the component name passed to
     *                                  {@code EnkanSystem.of(...)} for the
     *                                  {@link ApplicationComponent}, e.g. {@code "app"}
     * @param requestAdapter            vendor event → {@link HttpRequest}
     * @param responseAdapter           {@link HttpResponse} → vendor response
     */
    @SuppressWarnings("unchecked")
    public static <E, R> FunctionInvoker<E, R> boot(
            Supplier<EnkanSystem> systemFactory,
            String applicationComponentName,
            RequestAdapter<E> requestAdapter,
            ResponseAdapter<R> responseAdapter) {

        EnkanSystem system = systemFactory.get();
        system.start();
        system.registerCrac();

        ApplicationComponent<HttpRequest, HttpResponse> appComponent =
                system.getComponent(applicationComponentName, ApplicationComponent.class);
        if (appComponent == null) {
            throw new IllegalStateException(
                    "ApplicationComponent '" + applicationComponentName
                            + "' not found in EnkanSystem");
        }
        Application<HttpRequest, HttpResponse> application = appComponent.getApplication();
        if (!(application instanceof WebApplication webApp)) {
            throw new IllegalStateException(
                    "Application is not a WebApplication: " + application);
        }
        return new FunctionInvoker<>(
                system, application, webApp::createRequest,
                requestAdapter, responseAdapter);
    }

    public R invoke(E event) {
        try {
            HttpRequest req = requestAdapter.toHttpRequest(event, requestFactory);
            HttpResponse res = application.handle(req);
            return responseAdapter.toResponse(res);
        } catch (RuntimeException ex) {
            LOG.log(Level.SEVERE, "Unhandled exception in middleware chain", ex);
            return responseAdapter.toResponse(FaasErrors.toInternalServerError(ex));
        }
    }

    /** Exposed for tests and for handlers that want to inspect components directly. */
    public EnkanSystem getSystem() {
        return system;
    }
}
