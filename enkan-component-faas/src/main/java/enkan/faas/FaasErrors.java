package enkan.faas;

import enkan.web.data.HttpResponse;

/**
 * Builds the canonical error responses returned to FaaS clients when the
 * middleware chain fails. Centralized so AWS, Azure, and GCP all surface the
 * same shape on uncaught exceptions.
 */
public final class FaasErrors {

    private FaasErrors() {}

    /**
     * Builds a 500 Internal Server Error response. The thrown exception's
     * message is intentionally NOT included in the body — that would leak
     * server internals through API Gateway, Function URL, etc. Operators
     * should consult logs (the throwable is logged by the invoker before
     * this is called).
     */
    public static HttpResponse toInternalServerError(Throwable cause) {
        HttpResponse res = HttpResponse.of("Internal Server Error");
        res.setStatus(500);
        res.setContentType("text/plain; charset=utf-8");
        return res;
    }
}
