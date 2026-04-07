package enkan.faas;

import enkan.Application;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Vendor-neutral base for streaming FaaS adapters.
 *
 * <p>Used by platforms whose response API is an output-stream sink rather than
 * a return value — notably Google Cloud Functions, where
 * {@code HttpFunction.service(req, res)} writes directly into
 * {@code res.getOutputStream()} and returns {@code void}.
 *
 * <p>Mirrors {@link FaasAdapter} but uses a two-argument {@link #invoke} that
 * takes the sink as a second parameter instead of returning a value.
 *
 * @param <E> the vendor event type
 * @param <S> the vendor response sink type
 */
public abstract class StreamingFaasAdapter<E, S> {

    private static final Logger LOG = Logger.getLogger(StreamingFaasAdapter.class.getName());

    private Application<HttpRequest, HttpResponse> application;
    private Supplier<HttpRequest> requestFactory;

    /**
     * Called by the owning component during {@code start()} to inject the
     * live {@link Application} and the per-request factory.
     */
    public void activate(Application<HttpRequest, HttpResponse> application,
                         Supplier<HttpRequest> requestFactory) {
        this.application = application;
        this.requestFactory = requestFactory;
    }

    /** Called by the owning component during {@code stop()} to release references. */
    public void deactivate() {
        this.application = null;
        this.requestFactory = null;
    }

    /**
     * Translates a vendor event into an Enkan {@link HttpRequest}.
     * Implementations must call {@code requestFactory.get()} to obtain the
     * pre-mixed request object.
     */
    protected abstract HttpRequest toHttpRequest(E event, Supplier<HttpRequest> requestFactory)
            throws IOException;

    /**
     * Writes the Enkan {@link HttpResponse} into the vendor's response sink.
     */
    protected abstract void writeTo(HttpResponse response, S sink) throws IOException;

    /**
     * Translates {@code event} through the Enkan middleware chain and writes
     * the result into {@code sink}. Catches {@link RuntimeException} and writes
     * a 500 response rather than propagating.
     */
    protected void invoke(E event, S sink) throws IOException {
        if (application == null) {
            throw new IllegalStateException(
                    "StreamingFaasAdapter has not been activated — EnkanSystem.start() must be called first");
        }
        HttpResponse response;
        try {
            HttpRequest req = toHttpRequest(event, requestFactory);
            response = application.handle(req);
        } catch (RuntimeException ex) {
            LOG.log(Level.SEVERE, "Unhandled exception in middleware chain", ex);
            response = FaasErrors.toInternalServerError(ex);
        }
        writeTo(response, sink);
    }
}
