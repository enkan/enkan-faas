package enkan.faas.azure;

import com.microsoft.azure.functions.HttpRequestMessage;
import enkan.faas.RequestAdapter;
import enkan.web.collection.Headers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Translates an Azure Functions {@link HttpRequestMessage} into an Enkan
 * {@link enkan.web.data.HttpRequest}. Stateless — safe to hold in a
 * {@code static final} field shared across Function invocations.
 */
public final class AzureHttpRequestAdapter
        implements RequestAdapter<HttpRequestMessage<Optional<String>>> {

    @Override
    public enkan.web.data.HttpRequest toHttpRequest(
            HttpRequestMessage<Optional<String>> event,
            Supplier<enkan.web.data.HttpRequest> requestFactory) {

        enkan.web.data.HttpRequest req = requestFactory.get();
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
}
