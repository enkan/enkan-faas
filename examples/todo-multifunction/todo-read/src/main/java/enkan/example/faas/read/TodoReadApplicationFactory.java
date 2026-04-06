package enkan.example.faas.read;

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

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only TODO API. Routes:
 *
 * <ul>
 *   <li>{@code GET /todos}        — list all</li>
 *   <li>{@code GET /todos/{id}}   — fetch one</li>
 * </ul>
 *
 * <p>This Function intentionally bundles only the read code path. The write
 * controller and any state-mutating dependencies live in a different Maven
 * module and are absent from this Lambda's deployment artifact.
 */
public class TodoReadApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {

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
                if (!"GET".equalsIgnoreCase(method)) {
                    return error(405, "Method Not Allowed");
                }
                if ("/todos".equals(uri)) {
                    return listAll();
                }
                Matcher m = ITEM_PATH.matcher(uri);
                if (m.matches()) {
                    return fetchOne(m.group(1));
                }
                return error(404, "Not Found");
            }
        });
        return app;
    }

    private static HttpResponse listAll() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Todo t : TodoStore.findAll()) {
            if (!first) sb.append(',');
            sb.append(toJson(t));
            first = false;
        }
        sb.append(']');
        return jsonResponse(200, sb.toString());
    }

    private static HttpResponse fetchOne(String id) {
        Optional<Todo> found = TodoStore.findById(id);
        return found.map(t -> jsonResponse(200, toJson(t)))
                .orElseGet(() -> error(404, "Not Found"));
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
