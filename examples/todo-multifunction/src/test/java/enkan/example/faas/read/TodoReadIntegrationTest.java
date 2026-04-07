package enkan.example.faas.read;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentRelationship;
import enkan.example.faas.shared.TodoStore;
import enkan.faas.aws.ApiGatewayV2Adapter;
import enkan.faas.aws.AwsLambdaComponent;
import enkan.system.EnkanSystem;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoReadIntegrationTest {

    private static ApiGatewayV2Adapter adapter;
    private static EnkanSystem system;

    @BeforeAll
    static void bootSystem() {
        adapter = new ApiGatewayV2Adapter();
        system = EnkanSystem.of(
                "app",    new ApplicationComponent<HttpRequest, HttpResponse>(
                                  TodoReadApplicationFactory.class.getName()),
                "lambda", new AwsLambdaComponent(adapter))
            .relationships(ComponentRelationship.component("lambda").using("app"));
        system.start();
    }

    @AfterAll
    static void stopSystem() {
        if (system != null) {
            system.stop();
        }
    }

    @BeforeEach
    void resetStore() {
        TodoStore.clear();
    }

    @Test
    void getTodosReturnsEmptyArrayWhenStoreEmpty() {
        APIGatewayV2HTTPResponse res = adapter.handleRequest(buildGet("/todos"), null);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).isEqualTo("[]");
        assertThat(res.getHeaders().get("Content-Type")).isEqualTo("application/json");
    }

    @Test
    void getTodosReturnsAllItems() {
        TodoStore.create("buy milk");
        TodoStore.create("write tests");

        APIGatewayV2HTTPResponse res = adapter.handleRequest(buildGet("/todos"), null);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy milk").contains("write tests");
    }

    @Test
    void getTodoByIdReturnsItemWhenFound() {
        var created = TodoStore.create("buy milk");

        APIGatewayV2HTTPResponse res = adapter.handleRequest(buildGet("/todos/" + created.id()), null);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy milk").contains(created.id());
    }

    @Test
    void getTodoByIdReturns404WhenMissing() {
        APIGatewayV2HTTPResponse res = adapter.handleRequest(buildGet("/todos/nonexistent"), null);

        assertThat(res.getStatusCode()).isEqualTo(404);
    }

    @Test
    void todoReadShadedJarDoesNotContainWriteClasses() throws Exception {
        // The point of per-Function bundling: the shaded JAR for todo-read must not
        // contain any classes from the write side. The enkan-faas-maven-plugin guarantees
        // this via BFS closure from the @FaasFunction-annotated ApplicationFactory class.
        java.nio.file.Path jar = java.nio.file.Path.of(
                System.getProperty("project.build.directory", "target"),
                "todo-read-shaded.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(jar),
                "todo-read-shaded.jar not present — run mvn package first");

        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            boolean hasWrite = jf.stream()
                    .anyMatch(e -> e.getName().contains("faas/write/"));
            assertThat(hasWrite)
                    .as("todo-read-shaded.jar must not contain write-side classes")
                    .isFalse();
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
