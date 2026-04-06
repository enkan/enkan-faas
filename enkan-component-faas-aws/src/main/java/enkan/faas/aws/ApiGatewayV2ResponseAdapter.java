package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.faas.ResponseAdapter;
import enkan.web.collection.Headers;
import enkan.web.data.HttpResponse;
import enkan.web.data.StreamingBody;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates an Enkan {@link HttpResponse} into an
 * {@link APIGatewayV2HTTPResponse} (API Gateway HTTP API v2 / Function URL).
 * Stateless and safe to hold in a {@code static final} field.
 *
 * <p>Behavior notes:
 * <ul>
 *   <li>{@code Set-Cookie} headers are extracted into the v2 response's
 *       {@code cookies} array — API Gateway v2 silently drops Set-Cookie if
 *       it appears as a regular header.</li>
 *   <li>String bodies whose Content-Type is textual are written verbatim;
 *       all other body types (InputStream, File, byte[], StreamingBody) are
 *       buffered and base64-encoded with {@code isBase64Encoded=true}.</li>
 *   <li>Null bodies produce a response with no body.</li>
 * </ul>
 */
public final class ApiGatewayV2ResponseAdapter
        implements ResponseAdapter<APIGatewayV2HTTPResponse> {

    @Override
    public APIGatewayV2HTTPResponse toResponse(HttpResponse response) {
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
                        // Caller has not declared a textual content type — base64 to be safe.
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
