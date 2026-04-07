package enkan.example.faas.write;

import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.component.ApplicationComponent;
import enkan.component.ComponentRelationship;
import enkan.example.faas.shared.Todo;
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

class TodoWriteIntegrationTest {

    private static ApiGatewayV2Adapter adapter;
    private static EnkanSystem system;

    @BeforeAll
    static void bootSystem() {
        adapter = new ApiGatewayV2Adapter();
        system = EnkanSystem.of(
                "app",    new ApplicationComponent<HttpRequest, HttpResponse>(
                                  TodoWriteApplicationFactory.class.getName()),
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
    void postCreatesTodoAndReturns200() {
        APIGatewayV2HTTPResponse res = adapter.handleRequest(
                buildEvent("POST", "/todos", "{\"title\":\"buy milk\"}"), null);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy milk");
        assertThat(TodoStore.findAll()).hasSize(1);
    }

    @Test
    void postWithoutTitleReturns400() {
        APIGatewayV2HTTPResponse res = adapter.handleRequest(
                buildEvent("POST", "/todos", "{}"), null);

        assertThat(res.getStatusCode()).isEqualTo(400);
    }

    @Test
    void putUpdatesExistingTodo() {
        Todo existing = TodoStore.create("buy milk");

        APIGatewayV2HTTPResponse res = adapter.handleRequest(
                buildEvent("PUT", "/todos/" + existing.id(), "{\"title\":\"buy oat milk\",\"done\":true}"), null);

        assertThat(res.getStatusCode()).isEqualTo(200);
        assertThat(res.getBody()).contains("buy oat milk").contains("true");
        assertThat(TodoStore.findById(existing.id())).hasValueSatisfying(t -> {
            assertThat(t.title()).isEqualTo("buy oat milk");
            assertThat(t.done()).isTrue();
        });
    }

    @Test
    void putReturns404WhenMissing() {
        APIGatewayV2HTTPResponse res = adapter.handleRequest(
                buildEvent("PUT", "/todos/nonexistent", "{\"title\":\"x\"}"), null);

        assertThat(res.getStatusCode()).isEqualTo(404);
    }

    @Test
    void deleteRemovesItem() {
        Todo existing = TodoStore.create("temp");

        APIGatewayV2HTTPResponse res = adapter.handleRequest(
                buildEvent("DELETE", "/todos/" + existing.id(), null), null);

        assertThat(res.getStatusCode()).isEqualTo(204);
        assertThat(TodoStore.findById(existing.id())).isEmpty();
    }

    @Test
    void todoWriteShadedJarDoesNotContainReadClasses() throws Exception {
        // The point of per-Function bundling: the shaded JAR for todo-write must not
        // contain any classes from the read side. The enkan-faas-maven-plugin guarantees
        // this via BFS closure from the @FaasFunction-annotated ApplicationFactory class.
        java.nio.file.Path jar = java.nio.file.Path.of(
                System.getProperty("project.build.directory", "target"),
                "todo-write-shaded.jar");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.exists(jar),
                "todo-write-shaded.jar not present — run mvn package first");

        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile())) {
            boolean hasRead = jf.stream()
                    .anyMatch(e -> e.getName().contains("faas/read/"));
            assertThat(hasRead)
                    .as("todo-write-shaded.jar must not contain read-side classes")
                    .isFalse();
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
