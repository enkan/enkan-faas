package enkan.example.faas.read.handler;

import enkan.example.faas.shared.ApiResponse;
import enkan.example.faas.shared.ProblemEncoder;
import enkan.example.faas.shared.TodoEncoder;
import enkan.example.faas.shared.TodoStore;
import enkan.web.data.HttpRequest;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-side handler for the TODO API.
 *
 * <p>Routes (resolved by URI inspection inside the handler, not by RoutingMiddleware):
 * <ul>
 *   <li>{@code GET /todos}       → list all</li>
 *   <li>{@code GET /todos/{id}}  → fetch one</li>
 * </ul>
 *
 * <p>All responses are returned as {@code Map<String, Object>} or {@link ApiResponse},
 * letting {@code SerDesMiddleware} + {@code JsonBodyWriter} handle serialization
 * without reflection on domain classes.
 */
public class TodoReadHandler {

    private static final Pattern ITEM_PATH = Pattern.compile("^/todos/([^/]+)$");

    public Object handle(HttpRequest request, JsonNode body) {
        Matcher m = ITEM_PATH.matcher(request.getUri());
        if (m.matches()) {
            return show(m.group(1));
        }
        return list();
    }

    private List<Map<String, Object>> list() {
        return TodoStore.findAll().stream()
                .map(TodoEncoder.TODO::encode)
                .toList();
    }

    private Object show(String id) {
        return TodoStore.findById(id)
                .<Object>map(TodoEncoder.TODO::encode)
                .orElseGet(() -> new ApiResponse(404, ProblemEncoder.notFound()));
    }
}
