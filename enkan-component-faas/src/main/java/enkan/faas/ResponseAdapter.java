package enkan.faas;

import enkan.web.data.HttpResponse;

/**
 * Translates an Enkan {@link HttpResponse} into a vendor-specific FaaS response.
 *
 * <p>Implementations must be stateless so they can be safely held in
 * {@code static final} fields shared across handler invocations.
 *
 * @param <R> the vendor response type, e.g. {@code APIGatewayV2HTTPResponse}
 */
@FunctionalInterface
public interface ResponseAdapter<R> {
    R toResponse(HttpResponse response);
}
