package enkan.faas.gcp;

import com.google.cloud.functions.HttpRequest;
import enkan.faas.RequestAdapter;
import enkan.web.collection.Headers;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Translates a GCP Functions Framework {@link HttpRequest} into an Enkan
 * {@link enkan.web.data.HttpRequest}. Works for both Google Cloud Functions
 * (Gen 2) and Cloud Run, which share the same HTTP abstraction. Stateless
 * and safe to hold in a {@code static final} field.
 */
public final class GcpHttpRequestAdapter
        implements RequestAdapter<HttpRequest> {

    @Override
    public enkan.web.data.HttpRequest toHttpRequest(
            HttpRequest event,
            Supplier<enkan.web.data.HttpRequest> requestFactory) {

        enkan.web.data.HttpRequest req = requestFactory.get();
        req.setRequestMethod(event.getMethod());
        req.setUri(event.getPath());
        req.setQueryString(event.getQuery().orElse(null));

        String uri = event.getUri();
        if (uri != null) {
            req.setScheme(uri.startsWith("https://") ? "https" : "http");
            try {
                java.net.URI parsed = java.net.URI.create(uri);
                req.setServerName(parsed.getHost());
                int port = parsed.getPort();
                req.setServerPort(port > 0 ? port : (
                        "https".equals(req.getScheme()) ? 443 : 80));
            } catch (IllegalArgumentException ignored) {
                req.setServerName("cloudrun.local");
                req.setServerPort(443);
            }
        } else {
            req.setScheme("https");
            req.setServerName("cloudrun.local");
            req.setServerPort(443);
        }

        Headers headers = Headers.empty();
        Map<String, List<String>> src = event.getHeaders();
        if (src != null) {
            src.forEach((name, values) -> {
                if (values != null) {
                    for (String v : values) {
                        headers.put(name, v);
                    }
                }
            });
        }
        req.setHeaders(headers);
        req.setContentType(event.getContentType().orElse(null));
        req.setContentLength(event.getContentLength() >= 0 ? event.getContentLength() : null);
        Optional<String> encoding = event.getCharacterEncoding();
        encoding.ifPresent(req::setCharacterEncoding);

        try {
            InputStream body = event.getInputStream();
            req.setBody(body != null ? body : InputStream.nullInputStream());
        } catch (IOException e) {
            req.setBody(InputStream.nullInputStream());
        }
        return req;
    }
}
