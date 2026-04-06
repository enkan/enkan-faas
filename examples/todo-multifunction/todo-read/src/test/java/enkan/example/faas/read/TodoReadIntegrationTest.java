package enkan.example.faas.read;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
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

class TodoReadIntegrationTest {

    private static FunctionInvoker<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> invoker;

    @BeforeAll
    static void bootSystem() {
        invoker = FunctionInvoker.boot(
                TodoReadSystemFactory::create,
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
    void getTodosReturnsEmptyArrayWhenStoreEmpty() {
        APIGatewayV2HTTPResponse res = invoker.invoke(buildGet("/todos"));

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("[]");
        assertThat(res.getHeaders().get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void getTodosReturnsAllItems() {
        TodoStore.create("buy milk");
        TodoStore.create("write tests");

        APIGatewayV2HTTPResponse res = invoker.invoke(buildGet("/todos"));

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy milk").contains("write tests");
    }

    @Test
    void getTodoByIdReturnsItemWhenFound() {
        var created = TodoStore.create("buy milk");

        APIGatewayV2HTTPResponse res = invoker.invoke(buildGet("/todos/" + created.id()));

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy milk").contains(created.id());
    }

    @Test
    void getTodoByIdReturns404WhenMissing() {
        APIGatewayV2HTTPResponse res = invoker.invoke(buildGet("/todos/nonexistent"));

        assertThat(res.getStatusCode()).isEqualTo(404);
    }

    @Test
    void postRequestIs405() {
        APIGatewayV2HTTPEvent event = buildGet("/todos");
        event.getRequestContext().getHttp().setMethod("POST");

        APIGatewayV2HTTPResponse res = invoker.invoke(event);

        assertThat(res.getStatusCode()).isEqualTo(405);
    }

    @Test
    void todoWriteControllerIsNotOnTheClasspath() {
        // The point of per-Function bundling: this Function does not contain
        // any classes from todo-write. If someone accidentally adds a dependency
        // on todo-write, this assertion fails loudly.
        assertThat(canLoad("enkan.example.faas.write.TodoWriteApplicationFactory")).isFalse();
    }

    private static boolean canLoad(String className) {
        try {
            Class.forName(className, false, TodoReadIntegrationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static APIGatewayV2HTTPEvent buildGet(String path) {
        APIGatewayV2HTTPEvent event = new APIGatewayV2HTTPEvent();
        event.setRawPath(path);

        APIGatewayV2HTTPEvent.RequestContext.Http http = new APIGatewayV2HTTPEvent.RequestContext.Http();
        http.setMethod("GET");
        http.setPath(path);
        http.setProtocol("HTTP/1.1");
        http.setSourceIp("127.0.0.1");

        APIGatewayV2HTTPEvent.RequestContext rc = new APIGatewayV2HTTPEvent.RequestContext();
        rc.setHttp(http);
        rc.setDomainName("test.local");
        event.setRequestContext(rc);

        Map<String, String> headers = new HashMap<>();
        event.setHeaders(headers);
        return event;
    }
}
