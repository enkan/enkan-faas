package enkan.faas.aws;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.web.collection.Headers;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class ApiGatewayV2ResponseAdapterTest {

    private final ApiGatewayV2ResponseAdapter adapter = new ApiGatewayV2ResponseAdapter();

    @Test
    void mapsStatusAndPlainTextBody() {
        HttpResponse res = HttpResponse.of("hello");
        res.setStatus(200);
        res.setContentType("text/plain");

        APIGatewayV2HTTPResponse out = adapter.toResponse(res);

        assertThat(out.getStatusCode()).isEqualTo(200);
        assertThat(out.getBody()).isEqualTo("hello");
        assertThat(out.getIsBase64Encoded()).isFalse();
        assertThat(out.getHeaders().get("Content-Type")).isEqualTo("text/plain");
    }

    @Test
    void jsonBodyIsNotBase64Encoded() {
        HttpResponse res = HttpResponse.of("{\"status\":\"ok\"}");
        res.setStatus(200);
        res.setContentType("application/json");

        APIGatewayV2HTTPResponse out = adapter.toResponse(res);

        assertThat(out.getBody()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(out.getIsBase64Encoded()).isFalse();
    }

    @Test
    void binaryInputStreamBodyIsBase64Encoded() {
        byte[] payload = new byte[]{0x01, 0x02, 0x03, (byte) 0xff};
        HttpResponse res = HttpResponse.of(new ByteArrayInputStream(payload));
        res.setStatus(200);
        res.setContentType("application/octet-stream");

        APIGatewayV2HTTPResponse out = adapter.toResponse(res);

        assertThat(out.getIsBase64Encoded()).isTrue();
        assertThat(Base64.getDecoder().decode(out.getBody())).containsExactly(payload);
    }

    @Test
    void setCookieHeaderIsLiftedIntoCookiesArray() {
        HttpResponse res = HttpResponse.of("ok");
        res.setStatus(200);
        Headers headers = Headers.empty();
        headers.put("Set-Cookie", "session=abc; Path=/");
        headers.put("Set-Cookie", "remember=1");
        headers.put("Content-Type", "text/plain");
        res.setHeaders(headers);

        APIGatewayV2HTTPResponse out = adapter.toResponse(res);

        assertThat(out.getCookies()).containsExactlyInAnyOrder("session=abc; Path=/", "remember=1");
        assertThat(out.getHeaders()).doesNotContainKey("Set-Cookie");
    }

    @Test
    void unspecifiedContentTypeForStringBodyKeepsAsText() {
        // No Content-Type header set → adapter defaults to treating as text.
        HttpResponse res = HttpResponse.of("plain");
        res.setStatus(200);

        APIGatewayV2HTTPResponse out = adapter.toResponse(res);

        assertThat(out.getBody()).isEqualTo("plain");
        assertThat(out.getIsBase64Encoded()).isFalse();
    }

    @Test
    void errorStatusIsPropagated() {
        HttpResponse res = HttpResponse.of("not found");
        res.setStatus(404);
        res.setContentType("text/plain");

        APIGatewayV2HTTPResponse out = adapter.toResponse(res);

        assertThat(out.getStatusCode()).isEqualTo(404);
    }
}
