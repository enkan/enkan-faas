package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Translates an Enkan {@link HttpResponse} into a legacy API Gateway REST (v1)
 * {@link APIGatewayProxyResponseEvent}.
 *
 * <p>REST API v1 requires {@code multiValueHeaders} for multi-value headers
 * (Set-Cookie, Cache-Control combinations, etc.) rather than a separate
 * cookies array. Single-value headers go into {@code headers}.
 */
public final class ApiGatewayRestResponseAdapter
        implements ResponseAdapter<APIGatewayProxyResponseEvent> {

    @Override
    public APIGatewayProxyResponseEvent toResponse(HttpResponse response) {
        APIGatewayProxyResponseEvent out = new APIGatewayProxyResponseEvent();
        out.setStatusCode(response.getStatus());

        Map<String, String> single = new HashMap<>();
        Map<String, List<String>> multi = new LinkedHashMap<>();
        Headers src = response.getHeaders();
        if (src != null) {
            src.forEachHeader((name, value) -> {
                String stringValue = value != null ? value.toString() : "";
                // If this is the second (or later) value for the same header name,
                // promote it to the multiValueHeaders map.
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
