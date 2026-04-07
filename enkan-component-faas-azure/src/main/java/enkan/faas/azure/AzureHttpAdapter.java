package enkan.faas.azure;

import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import enkan.faas.FaasAdapter;
import enkan.web.collection.Headers;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import enkan.web.data.StreamingBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * FaaS adapter for Azure Functions.
 *
 * <p>Merges the former {@code AzureHttpRequestAdapter} and {@code AzureResponseWriter}
 * into a single component-style adapter.
 *
 * <p>Azure's response builder is tied to the incoming request object
 * ({@code request.createResponseBuilder(status)}). The standard
 * {@link FaasAdapter#invoke} path cannot carry the Azure request object through
 * to {@link #toResponse}, so this adapter stores it in a {@link ThreadLocal}
 * for the duration of each invocation. This is safe because Lambda/Azure
 * Functions use one thread per concurrent invocation.
 *
 * <p>Use {@link #handleRequest(HttpRequestMessage)} from the generated handler.
 */
public final class AzureHttpAdapter
        extends FaasAdapter<HttpRequestMessage<Optional<String>>, HttpResponseMessage> {

    // The Azure request is needed in toResponse() to call createResponseBuilder().
    // A ThreadLocal is used so the stateless adapter remains safe for concurrent use.
    private final ThreadLocal<HttpRequestMessage<?>> currentRequest = new ThreadLocal<>();

    /**
     * Handles a single Azure Functions invocation end-to-end.
     * Generated handler classes delegate to this method.
     */
    public HttpResponseMessage handleRequest(HttpRequestMessage<Optional<String>> request) {
        currentRequest.set(request);
        try {
            return invoke(request);
        } finally {
            currentRequest.remove();
        }
    }

    // -----------------------------------------------------------------------
    // Request translation (formerly AzureHttpRequestAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected HttpRequest toHttpRequest(
            HttpRequestMessage<Optional<String>> event,
            Supplier<HttpRequest> requestFactory) {

        HttpRequest req = requestFactory.get();
        req.setRequestMethod(event.getHttpMethod() != null
                ? event.getHttpMethod().name() : "GET");

        URI uri = event.getUri();
        if (uri != null) {
            req.setUri(uri.getPath() != null ? uri.getPath() : "/");
            req.setQueryString(uri.getQuery());
            req.setScheme(uri.getScheme() != null ? uri.getScheme() : "https");
            req.setServerName(uri.getHost() != null ? uri.getHost() : "azure.local");
            int port = uri.getPort();
            req.setServerPort(port > 0 ? port : ("https".equals(req.getScheme()) ? 443 : 80));
        } else {
            req.setUri("/");
            req.setScheme("https");
            req.setServerName("azure.local");
            req.setServerPort(443);
        }

        Headers headers = Headers.empty();
        Map<String, String> raw = event.getHeaders();
        if (raw != null) {
            raw.forEach(headers::put);
        }
        req.setHeaders(headers);
        req.setContentType(headers.get("Content-Type"));
        String contentLength = headers.get("Content-Length");
        if (contentLength != null) {
            try { req.setContentLength(Long.parseLong(contentLength)); }
            catch (NumberFormatException ignored) {}
        }

        String body = event.getBody() != null ? event.getBody().orElse("") : "";
        req.setBody(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return req;
    }

    // -----------------------------------------------------------------------
    // Response translation (formerly AzureResponseWriter)
    // -----------------------------------------------------------------------

    @Override
    protected HttpResponseMessage toResponse(HttpResponse response) {
        HttpRequestMessage<?> request = currentRequest.get();
        if (request == null) {
            throw new IllegalStateException(
                    "No Azure request in context — call handleRequest() not invoke() directly");
        }
        HttpStatusType status = mapStatus(response.getStatus());
        HttpResponseMessage.Builder builder = request.createResponseBuilder(status);

        Headers headers = response.getHeaders();
        if (headers != null) {
            headers.forEachHeader((name, value) -> {
                if (value != null) {
                    builder.header(name, value.toString());
                }
            });
        }

        Object materialized = materializeBody(response.getBody());
        if (materialized != null) {
            builder.body(materialized);
        }
        return builder.build();
    }

    private static HttpStatusType mapStatus(int code) {
        try {
            return HttpStatus.valueOf(code);
        } catch (IllegalArgumentException e) {
            return HttpStatusType.custom(code);
        }
    }

    private static Object materializeBody(Object body) {
        try {
            return switch (body) {
                case null -> null;
                case String s -> s;
                case byte[] bytes -> bytes;
                case InputStream in -> in.readAllBytes();
                case File file -> Files.readAllBytes(file.toPath());
                case StreamingBody streaming -> {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    streaming.writeTo(buf);
                    yield buf.toByteArray();
                }
                default -> body.toString();
            };
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize response body for Azure", e);
        }
    }
}
