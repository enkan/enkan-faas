package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import enkan.web.collection.Headers;
import enkan.web.data.DefaultHttpRequest;
import enkan.web.data.HttpRequest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayV2RequestAdapterTest {

    private final ApiGatewayV2Adapter adapter = new ApiGatewayV2Adapter();
    private final Supplier<HttpRequest> factory = DefaultHttpRequest::new;

    @Test
    void mapsBasicGetRequest() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/todos", null, false);
        event.setRawQueryString("limit=10");

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getRequestMethod()).isEqualTo("GET");
        assertThat(req.getUri()).isEqualTo("/todos");
        assertThat(req.getQueryString()).isEqualTo("limit=10");
        assertThat(req.getScheme()).isEqualTo("https");
        assertThat(req.getServerPort()).isEqualTo(443);
        assertThat(req.getServerName()).isEqualTo("api.example.com");
        assertThat(req.getRemoteAddr()).isEqualTo("203.0.113.42");
    }

    @Test
    void copiesHeadersIntoEnkanHeaders() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/todos", null, false);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-Custom", "abc");
        event.setHeaders(headers);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        Headers enkan = req.getHeaders();
        assertThat(enkan.get("Content-Type")).isEqualTo("application/json");
        assertThat(enkan.get("X-Custom")).isEqualTo("abc");
        // Case-insensitive lookup works.
        assertThat(enkan.get("content-type")).isEqualTo("application/json");
    }

    @Test
    void reassemblesV2CookiesIntoSingleCookieHeader() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/todos", null, false);
        event.setCookies(List.of("session=abc123", "remember=1"));

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getHeaders().get("Cookie")).isEqualTo("session=abc123; remember=1");
    }

    @Test
    void plainTextBodyDecodesUtf8() throws IOException {
        APIGatewayV2HTTPEvent event = baseEvent("POST", "/todos", "hello", false);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(((ByteArrayInputStream) req.getBody()).available()).isEqualTo(5);
        assertThat(new String(req.getBody().readAllBytes())).isEqualTo("hello");
    }

    @Test
    void base64BodyDecodesBytes() throws IOException {
        byte[] raw = new byte[]{0x01, 0x02, 0x03, (byte) 0xff};
        String encoded = Base64.getEncoder().encodeToString(raw);
        APIGatewayV2HTTPEvent event = baseEvent("POST", "/upload", encoded, true);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getBody().readAllBytes()).containsExactly(raw);
    }

    @Test
    void emptyBodyProducesEmptyStream() throws IOException {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/todos", null, false);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getBody().readAllBytes()).isEmpty();
    }

    @Test
    void contentLengthHeaderIsParsed() {
        APIGatewayV2HTTPEvent event = baseEvent("POST", "/todos", "abcd", false);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Length", "4");
        headers.put("Content-Type", "text/plain");
        event.setHeaders(headers);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getContentLength()).isEqualTo(4L);
        assertThat(req.getContentType()).isEqualTo("text/plain");
    }

    // --- stripStagePrefix -------------------------------------------------

    @Test
    void namedStageIsPrefixStripped() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/prod/todos", null, false);
        event.getRequestContext().setStage("prod");

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getUri()).isEqualTo("/todos");
    }

    @Test
    void namedStageRootPathBecomesSlash() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/prod", null, false);
        event.getRequestContext().setStage("prod");

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getUri()).isEqualTo("/");
    }

    @Test
    void defaultStageDoesNotStripPrefix() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/todos", null, false);
        event.getRequestContext().setStage("$default");

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getUri()).isEqualTo("/todos");
    }

    @Test
    void nullStageDoesNotStripPrefix() {
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/todos", null, false);
        event.getRequestContext().setStage(null);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getUri()).isEqualTo("/todos");
    }

    @Test
    void stageNameNotPartOfPathIsLeftAlone() {
        // rawPath does not start with /prod — should not be modified
        APIGatewayV2HTTPEvent event = baseEvent("GET", "/other/todos", null, false);
        event.getRequestContext().setStage("prod");

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getUri()).isEqualTo("/other/todos");
    }

    private static APIGatewayV2HTTPEvent baseEvent(String method, String path, String body, boolean base64) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setVersion("2.0");
        event.setRouteKey(method + " " + path);
        event.setRawPath(path);

        APIGatewayV2HTTPEvent.RequestContext.Http http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setMethod(method);
        http.setPath(path);
        http.setProtocol("HTTP/1.1");
        http.setSourceIp("203.0.113.42");

        APIGatewayV2HTTPEvent.RequestContext rc = new APIGatewayV2HTTPEvent.RequestContext();
        rc.setHttp(http);
        rc.setDomainName("api.example.com");
        event.setRequestContext(rc);

        event.setBody(body);
        event.setIsBase64Encoded(base64);
        return event;
    }
}
