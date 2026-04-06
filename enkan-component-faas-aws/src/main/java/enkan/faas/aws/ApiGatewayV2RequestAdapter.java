package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import enkan.faas.RequestAdapter;
import enkan.web.collection.Headers;
import enkan.web.data.HttpRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Translates an {@link APIGatewayV2HTTPEvent} (API Gateway HTTP API v2 / Lambda
 * Function URL) into an Enkan {@link HttpRequest}. Stateless and safe to hold
 * in a {@code static final} field.
 *
 * <p>Behavior notes:
 * <ul>
 *   <li>Method, raw path, raw query string, source IP, and domain name are
 *       lifted from the v2 event's request context.</li>
 *   <li>HTTPS is assumed (API Gateway always TLS-terminates).</li>
 *   <li>v2 separates request cookies into a {@code cookies} array; this
 *       adapter re-emits them as a single {@code Cookie: a=1; b=2} header so
 *       Enkan's existing cookie middleware can parse them.</li>
 *   <li>If the body is base64-encoded the bytes are decoded; otherwise the
 *       string is encoded with the request's character set (default UTF-8).</li>
 * </ul>
 */
public final class ApiGatewayV2RequestAdapter
        implements RequestAdapter<APIGatewayV2HTTPEvent> {

    @Override
    public HttpRequest toHttpRequest(APIGatewayV2HTTPEvent event,
                                     Supplier<HttpRequest> requestFactory) {
        HttpRequest request = requestFactory.get();

        APIGatewayV2HTTPEvent.RequestContext rc = event.getRequestContext();
        APIGatewayV2HTTPEvent.RequestContext.Http http = rc != null ? rc.getHttp() : null;

        request.setRequestMethod(http != null ? http.getMethod() : "GET");
        request.setProtocol(http != null && http.getProtocol() != null
                ? http.getProtocol() : "HTTP/1.1");
        request.setUri(event.getRawPath() != null ? event.getRawPath() : "/");
        request.setQueryString(event.getRawQueryString());
        request.setScheme("https");
        request.setServerName(rc != null && rc.getDomainName() != null
                ? rc.getDomainName() : "lambda.local");
        request.setServerPort(443);
        request.setRemoteAddr(http != null ? http.getSourceIp() : null);

        Headers headers = buildHeaders(event);
        request.setHeaders(headers);
        request.setContentType(headers.get("Content-Type"));
        Long contentLength = parseContentLength(headers.get("Content-Length"));
        request.setContentLength(contentLength);
        Charset charset = extractCharset(headers.get("Content-Type"));
        if (charset != null) {
            request.setCharacterEncoding(charset.name());
        }

        request.setBody(decodeBody(event));
        return request;
    }

    private static Headers buildHeaders(APIGatewayV2HTTPEvent event) {
        Headers headers = Headers.empty();
        Map<String, String> raw = event.getHeaders();
        if (raw != null) {
            raw.forEach((name, value) -> {
                if (value == null) return;
                // v2 collapses multi-value headers into comma-separated values.
                if (value.indexOf(',') >= 0
                        && !"User-Agent".equalsIgnoreCase(name)
                        && !"Date".equalsIgnoreCase(name)) {
                    for (String part : value.split(",")) {
                        headers.put(name, part.trim());
                    }
                } else {
                    headers.put(name, value);
                }
            });
        }
        // v2 surfaces request cookies in a separate array; reassemble into a
        // single Cookie header so downstream middleware can parse them.
        List<String> cookies = event.getCookies();
        if (cookies != null && !cookies.isEmpty()) {
            headers.put("Cookie", String.join("; ", cookies));
        }
        return headers;
    }

    private static InputStream decodeBody(APIGatewayV2HTTPEvent event) {
        String body = event.getBody();
        if (body == null || body.isEmpty()) {
            return InputStream.nullInputStream();
        }
        if (event.getIsBase64Encoded()) {
            return new ByteArrayInputStream(Base64.getDecoder().decode(body));
        }
        Charset charset = extractCharset(event.getHeaders() != null
                ? event.getHeaders().get("Content-Type") : null);
        if (charset == null) charset = StandardCharsets.UTF_8;
        return new ByteArrayInputStream(body.getBytes(charset));
    }

    private static Long parseContentLength(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Charset extractCharset(String contentType) {
        if (contentType == null) return null;
        int idx = contentType.toLowerCase().indexOf("charset=");
        if (idx < 0) return null;
        String name = contentType.substring(idx + "charset=".length()).trim();
        int sc = name.indexOf(';');
        if (sc >= 0) name = name.substring(0, sc).trim();
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }
        try {
            return Charset.forName(name);
        } catch (Exception ignored) {
            return null;
        }
    }
}
