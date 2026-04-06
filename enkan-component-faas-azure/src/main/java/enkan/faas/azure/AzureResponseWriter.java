package enkan.faas.azure;

import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.HttpStatusType;
import enkan.web.collection.Headers;
import enkan.web.data.StreamingBody;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Translates an Enkan {@link enkan.web.data.HttpResponse} into an Azure
 * Functions {@link HttpResponseMessage}.
 *
 * <p>Azure's response builder is obtained from the request
 * ({@code request.createResponseBuilder(status)}), which means a stateless
 * {@code ResponseAdapter<HttpResponseMessage>} cannot build the response —
 * it needs the request too. Rather than contorting the core interface, this
 * writer is a standalone static helper. Handler code calls it directly:
 *
 * <pre>{@code
 * @FunctionName("todo")
 * public HttpResponseMessage run(
 *         @HttpTrigger(...) HttpRequestMessage<Optional<String>> req,
 *         ExecutionContext ctx) {
 *     HttpRequest enkanReq = REQUEST_ADAPTER.toHttpRequest(req, REQUEST_FACTORY);
 *     HttpResponse enkanRes = APPLICATION.handle(enkanReq);
 *     return AzureResponseWriter.write(req, enkanRes);
 * }
 * }</pre>
 */
public final class AzureResponseWriter {

    private AzureResponseWriter() {}

    public static HttpResponseMessage write(HttpRequestMessage<?> request,
                                            enkan.web.data.HttpResponse response) {
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

        Object body = response.getBody();
        Object materialized = materializeBody(body);
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
