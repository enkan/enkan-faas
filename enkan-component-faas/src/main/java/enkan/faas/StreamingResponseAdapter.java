package enkan.faas;

import enkan.web.data.HttpResponse;

import java.io.IOException;

/**
 * Writes an Enkan {@link HttpResponse} into a vendor-provided sink.
 *
 * <p>Used for FaaS platforms that expose an output-stream / writer style
 * response API instead of a value-returning one (notably Google Cloud Functions
 * Java, where {@code HttpFunction.service(req, res)} writes directly into
 * {@code res.getOutputStream()}). For such platforms a value-returning
 * {@link ResponseAdapter} is a poor fit because it forces the entire response
 * body into memory before any byte is sent. {@code StreamingResponseAdapter}
 * preserves true streaming.
 *
 * @param <R> the vendor sink type, e.g. {@code com.google.cloud.functions.HttpResponse}
 */
@FunctionalInterface
public interface StreamingResponseAdapter<R> {
    void writeTo(HttpResponse response, R sink) throws IOException;
}
