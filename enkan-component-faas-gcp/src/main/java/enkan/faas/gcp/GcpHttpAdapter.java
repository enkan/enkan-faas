package enkan.faas.gcp;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import enkan.faas.StreamingFaasAdapter;
import enkan.web.collection.Headers;
import enkan.web.data.StreamingBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * FaaS adapter for Google Cloud Functions / Cloud Run.
 *
 * <p>Merges the former {@code GcpHttpRequestAdapter} and
 * {@code GcpHttpResponseAdapter} into a single component-style adapter.
 * Implements {@link HttpFunction} directly, so the generated handler delegates
 * to {@link #service} without any indirection.
 *
 * <p>Uses true streaming — the response body is piped directly into
 * {@code sink.getOutputStream()} without buffering in memory.
 */
public final class GcpHttpAdapter
        extends StreamingFaasAdapter<HttpRequest, HttpResponse>
        implements HttpFunction {

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        invoke(request, response);
    }

    // -----------------------------------------------------------------------
    // Request translation (formerly GcpHttpRequestAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected enkan.web.data.HttpRequest toHttpRequest(
            HttpRequest event,
            Supplier<enkan.web.data.HttpRequest> requestFactory) throws IOException {

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

        InputStream body = event.getInputStream();
        req.setBody(body != null ? body : InputStream.nullInputStream());
        return req;
    }

    // -----------------------------------------------------------------------
    // Response translation (formerly GcpHttpResponseAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected void writeTo(enkan.web.data.HttpResponse response, HttpResponse sink)
            throws IOException {
        sink.setStatusCode(response.getStatus());

        Headers headers = response.getHeaders();
        if (headers != null) {
            headers.forEachHeader((name, value) -> {
                if (value != null) {
                    sink.appendHeader(name, value.toString());
                }
            });
        }

        Object body = response.getBody();
        if (body == null) return;

        OutputStream out = sink.getOutputStream();
        switch (body) {
            case String s -> out.write(s.getBytes(StandardCharsets.UTF_8));
            case byte[] bytes -> out.write(bytes);
            case InputStream in -> in.transferTo(out);
            case File file -> Files.copy(file.toPath(), out);
            case StreamingBody streaming -> streaming.writeTo(out);
            default -> throw new IllegalStateException(
                    "Unsupported HttpResponse body type: " + body.getClass());
        }
        out.flush();
    }
}
