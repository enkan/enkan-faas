package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import enkan.web.collection.Headers;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayRestResponseAdapterTest {

    private final ApiGatewayRestResponseAdapter adapter = new ApiGatewayRestResponseAdapter();

    @Test
    void mapsSimpleJsonResponse() {
        HttpResponse src = HttpResponse.of("{\"ok\":true}");
        src.setStatus(200);
        src.setContentType("application/json");

        APIGatewayProxyResponseEvent out = adapter.toResponse(src);

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).isEqualTo("{\"ok\":true}");
        assertThat(out.getIsBase64Encoded()).isFalse();
        assertThat(out.getHeaders().get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void multipleSetCookieHeadersPromoteToMultiValueHeaders() {
        HttpResponse src = HttpResponse.of("");
        src.setStatus(204);
        Headers h = Headers.empty();
        h.put("Set-Cookie", "a=1");
        h.put("Set-Cookie", "b=2");
        src.setHeaders(h);

        APIGatewayProxyResponseEvent out = adapter.toResponse(src);

        assertThat(out.getHeaders()).doesNotContainKey("Set-Cookie");
        assertThat(out.getMultiValueHeaders().get("Set-Cookie"))
                .containsExactly("a=1", "b=2");
    }

    @Test
    void binaryBodyIsBase64Encoded() {
        byte[] payload = {0x01, 0x02};
        HttpResponse src = HttpResponse.of(new java.io.ByteArrayInputStream(payload));
        src.setStatus(200);
        src.setContentType("application/octet-stream");

        APIGatewayProxyResponseEvent out = adapter.toResponse(src);

        assertThat(out.getIsBase64Encoded()).isTrue();
    }
}
