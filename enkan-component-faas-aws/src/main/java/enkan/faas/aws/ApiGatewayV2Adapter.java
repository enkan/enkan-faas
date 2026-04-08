package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * FaaS adapter for AWS API Gateway HTTP API v2 / Lambda Function URL.
 *
 * <p>Merges the former {@code ApiGatewayV2RequestAdapter} and
 * {@code ApiGatewayV2ResponseAdapter} into a single Enkan component-style
 * adapter. Activate via {@link AwsLambdaComponent}; then use as the
 * Lambda {@link RequestHandler} directly:
 *
 * <pre>{@code
 * @FaasFunction(name = "my-function", adapter = ApiGatewayV2Adapter.class)
 * public class MyApplicationFactory implements ApplicationFactory { ... }
 * }</pre>
 *
 * <p>The Maven plugin generates the handler class; this adapter is stateless
 * and safe to hold in a {@code static final} field.
 */
public final class ApiGatewayV2Adapter
        extends FaasAdapter<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse>
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx) {
        return invoke(event);
    }

    // -----------------------------------------------------------------------
    // Request translation (formerly ApiGatewayV2RequestAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected HttpRequest toHttpRequest(APIGatewayV2HTTPEvent event,
                                        Supplier<HttpRequest> requestFactory) {
        HttpRequest request = requestFactory.get();

        APIGatewayV2HTTPEvent.RequestContext rc = event.getRequestContext();
        APIGatewayV2HTTPEvent.RequestContext.Http http = rc != null ? rc.getHttp() : null;

        request.setRequestMethod(http != null ? http.getMethod() : "GET");
        request.setProtocol(http != null && http.getProtocol() != null
                ? http.getProtocol() : "HTTP/1.1");
        request.setUri(stripStagePrefix(event.getRawPath(), rc));
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

    /**
     * Returns the request path with the API Gateway stage prefix removed.
     *
     * <p>API Gateway HTTP API v2 with a named stage (e.g. {@code prod}) prepends
     * {@code /<stage>} to {@code rawPath}: a request to {@code /todos} arrives
     * as {@code rawPath=/prod/todos}. Handlers compare against bare paths like
     * {@code /todos}, so the stage prefix must be stripped here.
     *
     * <p>The {@code $default} stage (indicated by an empty string or {@code "$default"}
     * in {@code rc.getStage()}) never prepends a prefix, so rawPath is returned as-is.
     */
    private static String stripStagePrefix(String rawPath,
                                           APIGatewayV2HTTPEvent.RequestContext rc) {
        if (rawPath == null) return "/";
        if (rc == null) return rawPath;
        String stage = rc.getStage();
        if (stage == null || stage.isEmpty() || "$default".equals(stage)) return rawPath;
        String prefix = "/" + stage;
        if (rawPath.startsWith(prefix + "/")) return rawPath.substring(prefix.length());
        if (rawPath.equals(prefix)) return "/";
        return rawPath;
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

    // -----------------------------------------------------------------------
    // Response translation (formerly ApiGatewayV2ResponseAdapter)
    // -----------------------------------------------------------------------

    @Override
    protected APIGatewayV2HTTPResponse toResponse(HttpResponse response) {
        APIGatewayV2HTTPResponse out = new APIGatewayV2HTTPResponse();
        out.setStatusCode(response.getStatus());

        Map<String, String> headerMap = new HashMap<>();
        List<String> cookies = new ArrayList<>();
        Headers src = response.getHeaders();
        if (src != null) {
            src.forEachHeader((name, value) -> {
                String stringValue = value != null ? value.toString() : "";
                if ("Set-Cookie".equalsIgnoreCase(name)) {
                    cookies.add(stringValue);
                } else {
                    // Last write wins for the simple Map<String,String> form;
                    // multi-value response headers should use multiValueHeaders.
                    headerMap.merge(name, stringValue, (a, b) -> a + "," + b);
                }
            });
        }
        out.setHeaders(headerMap);
        if (!cookies.isEmpty()) {
            out.setCookies(cookies);
        }

        writeBody(out, response, headerMap.get("Content-Type"));
        return out;
    }

    private static void writeBody(APIGatewayV2HTTPResponse out,
                                  HttpResponse response,
                                  String contentType) {
        Object body = response.getBody();
        try {
            switch (body) {
                case null -> {
                    // no body
                }
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
                    if (isTextualContentType(contentType)) {
                        out.setBody(new String(bytes, StandardCharsets.UTF_8));
                        out.setIsBase64Encoded(false);
                    } else {
                        out.setBody(Base64.getEncoder().encodeToString(bytes));
                        out.setIsBase64Encoded(true);
                    }
                }
                case InputStream stream -> {
                    byte[] bytes2 = stream.readAllBytes();
                    if (isTextualContentType(contentType)) {
                        out.setBody(new String(bytes2, StandardCharsets.UTF_8));
                        out.setIsBase64Encoded(false);
                    } else {
                        out.setBody(Base64.getEncoder().encodeToString(bytes2));
                        out.setIsBase64Encoded(true);
                    }
                }
                case File file -> {
                    byte[] bytes3 = Files.readAllBytes(file.toPath());
                    if (isTextualContentType(contentType)) {
                        out.setBody(new String(bytes3, StandardCharsets.UTF_8));
                        out.setIsBase64Encoded(false);
                    } else {
                        out.setBody(Base64.getEncoder().encodeToString(bytes3));
                        out.setIsBase64Encoded(true);
                    }
                }
                case StreamingBody streaming -> {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    streaming.writeTo(buffer);
                    byte[] bytes4 = buffer.toByteArray();
                    if (isTextualContentType(contentType)) {
                        out.setBody(new String(bytes4, StandardCharsets.UTF_8));
                        out.setIsBase64Encoded(false);
                    } else {
                        out.setBody(Base64.getEncoder().encodeToString(bytes4));
                        out.setIsBase64Encoded(true);
                    }
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
