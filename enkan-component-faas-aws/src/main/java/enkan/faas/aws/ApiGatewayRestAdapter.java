package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FaaS adapter for legacy AWS API Gateway REST API (v1).
 *
 * <p>Merges the former {@code ApiGatewayRestRequestAdapter} and
 * {@code ApiGatewayRestResponseAdapter} into a single component-style adapter.
 * New work should prefer {@link ApiGatewayV2Adapter} with HTTP API v2.
 */
public final class ApiGatewayRestAdapter
        extends FaasAdapter<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent>
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context ctx) {
        return invoke(event);
    }

    // -----------------------------------------------------------------------
    // Request translation (formerly ApiGatewayRestRequestAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected HttpRequest toHttpRequest(APIGatewayProxyRequestEvent event,
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

    // -----------------------------------------------------------------------
    // Response translation (formerly ApiGatewayRestResponseAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected APIGatewayProxyResponseEvent toResponse(HttpResponse response) {
        APIGatewayProxyResponseEvent out = new APIGatewayProxyResponseEvent();
        out.setStatusCode(response.getStatus());

        Map<String, String> single = new HashMap<>();
        Map<String, List<String>> multi = new LinkedHashMap<>();
        Headers src = response.getHeaders();
        if (src != null) {
            src.forEachHeader((name, value) -> {
                String stringValue = value != null ? value.toString() : "";
                if (single.containsKey(name)) {
                    List<String> list = multi.computeIfAbsent(name, k -> {
                        List<String> init = new ArrayList<>();
                        init.add(single.remove(name));
                        return init;
                    });
                    list.add(stringValue);
                } else if (multi.containsKey(name)) {
                    multi.get(name).add(stringValue);
                } else {
                    single.put(name, stringValue);
                }
            });
        }
        out.setHeaders(single);
        if (!multi.isEmpty()) {
            out.setMultiValueHeaders(multi);
        }

        writeBody(out, response, single.get("Content-Type"));
        return out;
    }

    private static void writeBody(APIGatewayProxyResponseEvent out,
                                  HttpResponse response,
                                  String contentType) {
        Object body = response.getBody();
        try {
            switch (body) {
                case null -> {}
                case String s -> {
                    if (isTextualContentType(contentType)) {
                        out.setBody(s);
                        out.setIsBase64Encoded(false);
                    } else {
                        out.setBody(Base64.getEncoder().encodeToString(
                                s.getBytes(StandardCharsets.UTF_8)));
                        out.setIsBase64Encoded(true);
                    }
                }
                case byte[] bytes -> {
                    out.setBody(Base64.getEncoder().encodeToString(bytes));
                    out.setIsBase64Encoded(true);
                }
                case InputStream stream -> {
                    out.setBody(Base64.getEncoder().encodeToString(stream.readAllBytes()));
                    out.setIsBase64Encoded(true);
                }
                case File file -> {
                    out.setBody(Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())));
                    out.setIsBase64Encoded(true);
                }
                case StreamingBody streaming -> {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    streaming.writeTo(buffer);
                    out.setBody(Base64.getEncoder().encodeToString(buffer.toByteArray()));
                    out.setIsBase64Encoded(true);
                }
                default -> throw new IllegalStateException(
                        "Unsupported HttpResponse body type: " + body.getClass());
            }
        } catch (IOException ex) {
            throw new RuntimeException("Failed to serialize response body", ex);
        }
    }

    private static boolean isTextualContentType(String contentType) {
        if (contentType == null) return true;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
                || lower.contains("json")
                || lower.contains("xml")
                || lower.contains("javascript")
                || lower.contains("html")
                || lower.contains("yaml");
    }
}
