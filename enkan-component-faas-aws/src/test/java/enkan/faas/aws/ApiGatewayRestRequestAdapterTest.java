package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import enkan.web.collection.Headers;
import enkan.web.data.DefaultHttpRequest;
import enkan.web.data.HttpRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayRestRequestAdapterTest {

    private final ApiGatewayRestRequestAdapter adapter = new ApiGatewayRestRequestAdapter();
    private final Supplier<HttpRequest> factory = DefaultHttpRequest::new;

    @Test
    void mapsBasicRestRequest() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/todos");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        event.setHeaders(headers);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getRequestMethod()).isEqualTo("GET");
        assertThat(req.getUri()).isEqualTo("/todos");
        assertThat(req.getHeaders().get("Content-Type")).isEqualTo("application/json");
        assertThat(req.getScheme()).isEqualTo("https");
    }

    @Test
    void multiValueHeadersArePreserved() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/todos");
        Map<String, List<String>> multi = new HashMap<>();
        multi.put("Accept", List.of("application/json", "text/plain"));
        event.setMultiValueHeaders(multi);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        Headers h = req.getHeaders();
        assertThat(h.getList("Accept")).containsExactly("application/json", "text/plain");
    }

    @Test
    void multiValueQueryStringIsFlattened() {
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("GET");
        event.setPath("/search");
        Map<String, List<String>> query = new HashMap<>();
        query.put("tag", List.of("java", "serverless"));
        event.setMultiValueQueryStringParameters(query);

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getQueryString()).contains("tag=java").contains("tag=serverless");
    }

    @Test
    void base64BodyIsDecoded() throws IOException {
        byte[] raw = {0x01, 0x02, (byte) 0xff};
        APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
        event.setHttpMethod("POST");
        event.setPath("/upload");
        event.setIsBase64Encoded(true);
        event.setBody(Base64.getEncoder().encodeToString(raw));

        HttpRequest req = adapter.toHttpRequest(event, factory);

        assertThat(req.getBody().readAllBytes()).containsExactly(raw);
    }
}
