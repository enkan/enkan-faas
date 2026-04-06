package enkan.example.faas.write;

import enkan.Application;
import enkan.Middleware;
import enkan.MiddlewareChain;
import enkan.config.ApplicationFactory;
import enkan.example.faas.shared.Todo;
import enkan.example.faas.shared.TodoStore;
import enkan.system.inject.ComponentInjector;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Write-side TODO API. Routes:
 *
 * <ul>
 *   <li>{@code POST   /todos}      — create</li>
 *   <li>{@code PUT    /todos/{id}} — update</li>
 *   <li>{@code DELETE /todos/{id}} — delete</li>
 * </ul>
 *
 * <p>This Function intentionally bundles only the mutating code path. The
 * read controller is not on this Lambda's classpath.
 */
public class TodoWriteApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {

    private static final Pattern ITEM_PATH = Pattern.compile("^/todos/([^/]+)$");

    @Override
    public Application<HttpRequest, HttpResponse> create(ComponentInjector injector) {
        WebApplication app = new WebApplication();
        app.use(new Middleware<HttpRequest, HttpResponse, HttpRequest, HttpResponse>() {
            @Override
            public <NNREQ, NNRES> HttpResponse handle(
                    HttpRequest req,
                    MiddlewareChain<HttpRequest, HttpResponse, NNREQ, NNRES> chain) {

                String uri = req.getUri();
                String method = req.getRequestMethod();
                String body = readBody(req);

                if ("POST".equalsIgnoreCase(method) && "/todos".equals(uri)) {
                    return create(body);
                }
                Matcher m = ITEM_PATH.matcher(uri);
                if (m.matches()) {
                    String id = m.group(1);
                    if ("PUT".equalsIgnoreCase(method)) return update(id, body);
                    if ("DELETE".equalsIgnoreCase(method)) return delete(id);
                }
                return error(404, "Not Found");
            }
        });
        return app;
    }

    private static HttpResponse create(String body) {
        String title = parseStringField(body, "title");
        if (title == null || title.isBlank()) {
            return error(400, "title is required");
        }
        Todo created = TodoStore.create(title);
        return jsonResponse(201, toJson(created));
    }

    private static HttpResponse update(String id, String body) {
        String title = parseStringField(body, "title");
        Boolean done = parseBooleanField(body, "done");
        Optional<Todo> updated = TodoStore.update(id, title, done);
        return updated.map(t -> jsonResponse(200, toJson(t)))
                .orElseGet(() -> error(404, "Not Found"));
    }

    private static HttpResponse delete(String id) {
        boolean removed = TodoStore.delete(id);
        if (!removed) return error(404, "Not Found");
        HttpResponse res = HttpResponse.of("");
        res.setStatus(204);
        return res;
    }

    private static String readBody(HttpRequest req) {
        try {
            byte[] bytes = req.getBody().readAllBytes();
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Bare-bones JSON field extractor. Real apps would use Jackson via
     * {@code enkan-component-jackson}; the example keeps the dependency
     * surface minimal so the JAR size measurement is honest.
     */
    private static String parseStringField(String json, String field) {
        if (json == null) return null;
        String marker = "\"" + field + "\"";
        int k = json.indexOf(marker);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + marker.length());
        if (colon < 0) return null;
        int quoteStart = json.indexOf('"', colon + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static Boolean parseBooleanField(String json, String field) {
        if (json == null) return null;
        String marker = "\"" + field + "\"";
        int k = json.indexOf(marker);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + marker.length());
        if (colon < 0) return null;
        String tail = json.substring(colon + 1).trim();
        if (tail.startsWith("true")) return Boolean.TRUE;
        if (tail.startsWith("false")) return Boolean.FALSE;
        return null;
    }

    private static String toJson(Todo t) {
        return "{\"id\":\"" + escape(t.id())
                + "\",\"title\":\"" + escape(t.title())
                + "\",\"done\":" + t.done() + "}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static HttpResponse jsonResponse(int status, String body) {
        HttpResponse res = HttpResponse.of(body);
        res.setStatus(status);
        res.setContentType("application/json");
        return res;
    }

    private static HttpResponse error(int status, String message) {
        return jsonResponse(status, "{\"error\":\"" + message + "\"}");
    }
}
