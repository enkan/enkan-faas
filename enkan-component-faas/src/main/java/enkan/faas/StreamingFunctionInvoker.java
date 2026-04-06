package enkan.faas;

import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.system.EnkanSystem;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Streaming counterpart to {@link FunctionInvoker}. Used by FaaS platforms
 * whose response API is an output-stream sink rather than a return value —
 * notably Google Cloud Functions Java, where
 * {@code HttpFunction.service(req, res)} writes directly into
 * {@code res.getOutputStream()} and returns {@code void}.
 *
 * <p>The shape mirrors {@link FunctionInvoker}: boot once, assign to a
 * {@code static final} field, invoke per request. The only difference is
 * that {@link #invoke(Object, Object)} takes the sink as a second argument
 * instead of returning a response.
 *
 * @param <E> the vendor event type
 * @param <R> the vendor sink type
 */
public final class StreamingFunctionInvoker<E, R> {
    private static final Logger LOG = Logger.getLogger(StreamingFunctionInvoker.class.getName());

    private final EnkanSystem system;
    private final Application<HttpRequest, HttpResponse> application;
    private final Supplier<HttpRequest> requestFactory;
    private final RequestAdapter<E> requestAdapter;
    private final StreamingResponseAdapter<R> responseAdapter;

    private StreamingFunctionInvoker(EnkanSystem system,
                                     Application<HttpRequest, HttpResponse> application,
                                     Supplier<HttpRequest> requestFactory,
                                     RequestAdapter<E> requestAdapter,
                                     StreamingResponseAdapter<R> responseAdapter) {
        this.system = system;
        this.application = application;
        this.requestFactory = requestFactory;
        this.requestAdapter = requestAdapter;
        this.responseAdapter = responseAdapter;
    }

    @SuppressWarnings("unchecked")
    public static <E, R> StreamingFunctionInvoker<E, R> boot(
            Supplier<EnkanSystem> systemFactory,
            String applicationComponentName,
            RequestAdapter<E> requestAdapter,
            StreamingResponseAdapter<R> responseAdapter) {

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
        return new StreamingFunctionInvoker<>(
                system, application, webApp::createRequest,
                requestAdapter, responseAdapter);
    }

    public void invoke(E event, R sink) throws IOException {
        HttpResponse response;
        try {
            HttpRequest req = requestAdapter.toHttpRequest(event, requestFactory);
            response = application.handle(req);
        } catch (RuntimeException ex) {
            LOG.log(Level.SEVERE, "Unhandled exception in middleware chain", ex);
            response = FaasErrors.toInternalServerError(ex);
        }
        responseAdapter.writeTo(response, sink);
    }

    public EnkanSystem getSystem() {
        return system;
    }
}
