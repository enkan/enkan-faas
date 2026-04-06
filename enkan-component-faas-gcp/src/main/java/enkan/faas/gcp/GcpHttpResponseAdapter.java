package enkan.faas.gcp;

import com.google.cloud.functions.HttpResponse;
import enkan.faas.StreamingResponseAdapter;
import enkan.web.collection.Headers;
import enkan.web.data.StreamingBody;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Writes an Enkan {@link enkan.web.data.HttpResponse} into a GCP Functions
 * Framework {@link HttpResponse} sink. Uses true streaming — the response
 * body is piped directly into {@code sink.getOutputStream()} without being
 * buffered in memory.
 */
public final class GcpHttpResponseAdapter
        implements StreamingResponseAdapter<HttpResponse> {

    @Override
    public void writeTo(enkan.web.data.HttpResponse response, HttpResponse sink)
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
        if (body == null) {
            return;
        }
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
