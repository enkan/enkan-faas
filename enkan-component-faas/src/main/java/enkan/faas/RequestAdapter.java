package enkan.faas;

import enkan.web.data.HttpRequest;

import java.util.function.Supplier;

/**
 * Translates a vendor-specific FaaS event into an Enkan {@link HttpRequest}.
 *
 * <p>The {@code requestFactory} parameter is the pre-mixed request supplier
 * obtained from {@code WebApplication.createRequest()}. Adapters MUST call
 * {@code requestFactory.get()} to construct the request — never
 * {@code new DefaultHttpRequest()} — so that mixin interfaces declared by the
 * registered middlewares (cookies, session, flash, params, etc.) are present.
 *
 * @param <E> the vendor event type, e.g. {@code APIGatewayV2HTTPEvent}
 */
@FunctionalInterface
public interface RequestAdapter<E> {
    HttpRequest toHttpRequest(E event, Supplier<HttpRequest> requestFactory);
}
