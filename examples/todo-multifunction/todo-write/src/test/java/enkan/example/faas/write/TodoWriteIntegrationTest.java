package enkan.example.faas.write;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.example.faas.shared.Todo;
import enkan.example.faas.shared.TodoStore;
import enkan.faas.FunctionInvoker;
import enkan.faas.aws.ApiGatewayV2RequestAdapter;
import enkan.faas.aws.ApiGatewayV2ResponseAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoWriteIntegrationTest {

    private static FunctionInvoker<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> invoker;

    @BeforeAll
    static void bootSystem() {
        invoker = FunctionInvoker.boot(
                TodoWriteSystemFactory::create,
                "app",
                new ApiGatewayV2RequestAdapter(),
                new ApiGatewayV2ResponseAdapter());
    }

    @AfterAll
    static void stopSystem() {
        if (invoker != null) {
            invoker.getSystem().stop();
        }
    }

    @BeforeEach
    void resetStore() {
        TodoStore.clear();
    }

    @Test
    void postCreatesTodoAndReturns201() {
        APIGatewayV2HTTPResponse res = invoker.invoke(
                buildEvent("POST", "/todos", "{\"title\":\"buy milk\"}"));

        assertThat(res.getStatusCode()).isEqualTo(201);
        assertThat(res.getBody()).contains("buy milk");
        assertThat(TodoStore.findAll()).hasSize(1);
    }

    @Test
    void postWithoutTitleReturns400() {
        APIGatewayV2HTTPResponse res = invoker.invoke(
                buildEvent("POST", "/todos", "{}"));

        assertThat(res.getStatusCode()).isEqualTo(400);
    }

    @Test
    void putUpdatesExistingTodo() {
        Todo existing = TodoStore.create("buy milk");

        APIGatewayV2HTTPResponse res = invoker.invoke(
                buildEvent("PUT", "/todos/" + existing.id(), "{\"title\":\"buy oat milk\",\"done\":true}"));

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy oat milk").contains("true");
        assertThat(TodoStore.findById(existing.id())).hasValueSatisfying(t -> {
            assertThat(t.title()).isEqualTo("buy oat milk");
            assertThat(t.done()).isTrue();
        });
    }

    @Test
    void putReturns404WhenMissing() {
        APIGatewayV2HTTPResponse res = invoker.invoke(
                buildEvent("PUT", "/todos/nonexistent", "{\"title\":\"x\"}"));

        assertThat(res.getStatusCode()).isEqualTo(404);
    }

    @Test
    void deleteRemovesItem() {
        Todo existing = TodoStore.create("temp");

        APIGatewayV2HTTPResponse res = invoker.invoke(
                buildEvent("DELETE", "/todos/" + existing.id(), null));

        assertThat(res.getStatusCode()).isEqualTo(204);
        assertThat(TodoStore.findById(existing.id())).isEmpty();
    }

    @Test
    void todoReadControllerIsNotOnTheClasspath() {
        // The point of per-Function bundling: this Function does not contain
        // any classes from todo-read.
        assertThat(canLoad("enkan.example.faas.read.TodoReadApplicationFactory")).isFalse();
    }

    private static boolean canLoad(String className) {
        try {
            Class.forName(className, false, TodoWriteIntegrationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static APIGatewayV2HTTPEvent buildEvent(String method, String path, String body) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setRawPath(path);
        event.setBody(body);
        event.setIsBase64Encoded(false);

        APIGatewayV2HTTPEvent.RequestContext.Http http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setMethod(method);
        http.setPath(path);
        http.setProtocol("HTTP/1.1");
        http.setSourceIp("127.0.0.1");

        APIGatewayV2HTTPEvent.RequestContext rc = new APIGatewayV2HTTPEvent.RequestContext();
        rc.setHttp(http);
        rc.setDomainName("test.local");
        event.setRequestContext(rc);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        event.setHeaders(headers);
        return event;
    }
}
