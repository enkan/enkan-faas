package enkan.example.faas.shared;

import enkan.web.data.HasBody;
import enkan.web.data.HasStatus;

/**
 * Minimal response carrier implementing {@link HasStatus} and {@link HasBody}.
 *
 * <p>{@code SerDesMiddleware} checks {@code instanceof HasStatus} / {@code HasBody}
 * to extract the status code and body, then serializes the body via {@code JsonBodyWriter}.
 * Returning an {@code ApiResponse} from a handler lets callers set a non-200 status
 * (e.g. 404) while still letting the middleware handle JSON serialization — no need to
 * hand-build an {@link enkan.web.data.HttpResponse}.
 */
public class ApiResponse implements HasStatus, HasBody {

    private int status;
    private Object body;

    public ApiResponse(int status, Object body) {
        this.status = status;
        this.body = body;
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int status) { this.status = status; }
    @Override public Object getBody() { return body; }
}
