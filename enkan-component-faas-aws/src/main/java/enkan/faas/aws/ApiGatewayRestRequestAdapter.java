package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
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
 * Translates a legacy API Gateway REST (v1) {@link APIGatewayProxyRequestEvent}
 * into an Enkan {@link HttpRequest}. Provided for users with existing REST
 * API stages — new work should prefer {@link ApiGatewayV2RequestAdapter} with
 * HTTP API v2 (cheaper, lower latency, simpler event shape).
 *
 * <p>The main difference from v2: multi-value headers and query string
 * parameters are surfaced through {@code multiValueHeaders} /
 * {@code multiValueQueryStringParameters} maps instead of comma-collapsed
 * strings and a separate {@code cookies} array.
 */
public final class ApiGatewayRestRequestAdapter
        implements RequestAdapter<APIGatewayProxyRequestEvent> {

    @Override
    public HttpRequest toHttpRequest(APIGatewayProxyRequestEvent event,
                                     Supplier<HttpRequest> requestFactory) {
        HttpRequest request = requestFactory.get();

        request.setRequestMethod(event.getHttpMethod() != null ? event.getHttpMethod() : "GET");
        request.setUri(event.getPath() != null ? event.getPath() : "/");
        request.setQueryString(buildQueryString(event));
        request.setScheme("https");
        request.setServerPort(443);

        var rc = event.getRequestContext();
        request.setServerName(rc != null && rc.getDomainName() != null
                ? rc.getDomainName() : "lambda.local");
        if (rc != null && rc.getIdentity() != null) {
            request.setRemoteAddr(rc.getIdentity().getSourceIp());
        }

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

    private static Headers buildHeaders(APIGatewayProxyRequestEvent event) {
        Headers headers = Headers.empty();
        Map<String, List<String>> multi = event.getMultiValueHeaders();
        if (multi != null && !multi.isEmpty()) {
            multi.forEach((name, values) -> {
                if (values != null) {
                    for (String v : values) headers.put(name, v);
                }
            });
            return headers;
        }
        Map<String, String> single = event.getHeaders();
        if (single != null) {
            single.forEach((name, value) -> {
                if (value != null) headers.put(name, value);
            });
        }
        return headers;
    }

    private static String buildQueryString(APIGatewayProxyRequestEvent event) {
        Map<String, List<String>> multi = event.getMultiValueQueryStringParameters();
        if (multi != null && !multi.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            multi.forEach((k, values) -> {
                if (values == null) return;
                for (String v : values) {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(k).append('=').append(v == null ? "" : v);
                }
            });
            return sb.length() > 0 ? sb.toString() : null;
        }
        Map<String, String> single = event.getQueryStringParameters();
        if (single != null && !single.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            single.forEach((k, v) -> {
                if (sb.length() > 0) sb.append('&');
                sb.append(k).append('=').append(v == null ? "" : v);
            });
            return sb.toString();
        }
        return null;
    }

    private static InputStream decodeBody(APIGatewayProxyRequestEvent event) {
        String body = event.getBody();
        if (body == null || body.isEmpty()) {
            return InputStream.nullInputStream();
        }
        Boolean base64 = event.getIsBase64Encoded();
        if (Boolean.TRUE.equals(base64)) {
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
