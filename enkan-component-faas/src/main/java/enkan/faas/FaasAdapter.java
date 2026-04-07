package enkan.faas;

import enkan.Application;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vendor-neutral base for FaaS adapters.
 *
 * <p>An adapter is the counterpart to {@code JettyAdapter}: it translates
 * vendor-specific FaaS events into Enkan {@link HttpRequest} objects, invokes
 * the {@link Application}, and converts the resulting {@link HttpResponse}
 * back into the vendor's response type.
 *
 * <p>The adapter is activated by the platform-specific {@code *Component}
 * (e.g. {@code AwsLambdaComponent}) during {@link enkan.system.EnkanSystem#start()},
 * which injects the {@link Application} and request factory. Before activation,
 * calling {@link #invoke} throws {@link IllegalStateException}.
 *
 * @param <E> the vendor event type
 * @param <R> the vendor response type
 */
public abstract class FaasAdapter<E, R> {

    private static final Logger LOG = Logger.getLogger(FaasAdapter.class.getName());

    private Application<HttpRequest, HttpResponse> application;
    private Supplier<HttpRequest> requestFactory;

    /**
     * Called by the owning component during {@code start()} to inject the
     * live {@link Application} and the per-request factory from
     * {@link WebApplication#createRequest()}.
     */
    public void activate(Application<HttpRequest, HttpResponse> application,
                         Supplier<HttpRequest> requestFactory) {
        this.application = application;
        this.requestFactory = requestFactory;
    }

    /**
     * Called by the owning component during {@code stop()} to release
     * references to the application and request factory.
     */
    public void deactivate() {
        this.application = null;
        this.requestFactory = null;
    }

    /**
     * Translates a vendor event into an Enkan {@link HttpRequest}.
     * Implementations must call {@code requestFactory.get()} to obtain the
     * pre-mixed request object; they must NOT instantiate it directly.
     */
    protected abstract HttpRequest toHttpRequest(E event, Supplier<HttpRequest> requestFactory);

    /** Translates an Enkan {@link HttpResponse} into the vendor's response type. */
    protected abstract R toResponse(HttpResponse response);

    /**
     * Translates {@code event} through the Enkan middleware chain and returns
     * the vendor response. Catches {@link RuntimeException} and returns a 500
     * response rather than propagating — FaaS runtimes handle exceptions poorly.
     */
    protected R invoke(E event) {
        if (application == null) {
            throw new IllegalStateException(
                    "FaasAdapter has not been activated — EnkanSystem.start() must be called first");
        }
        try {
            HttpRequest req = toHttpRequest(event, requestFactory);
            HttpResponse res = application.handle(req);
            return toResponse(res);
        } catch (RuntimeException ex) {
            LOG.log(Level.SEVERE, "Unhandled exception in middleware chain", ex);
            return toResponse(FaasErrors.toInternalServerError(ex));
        }
    }
}
