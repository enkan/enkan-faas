package enkan.example.faas.write.handler;

import enkan.example.faas.shared.ApiResponse;
import enkan.example.faas.shared.ProblemEncoder;
import enkan.example.faas.shared.TodoEncoder;
import enkan.example.faas.shared.TodoStore;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import net.unit8.raoh.decode.Decoder;
import net.unit8.raoh.json.JsonDecoders;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Write-side handler for the TODO API.
 *
 * <p>Routes (resolved by URI inspection inside the handler):
 * <ul>
 *   <li>{@code POST   /todos}       - create</li>
 *   <li>{@code PUT    /todos/{id}}  - update</li>
 *   <li>{@code DELETE /todos/{id}}  - delete</li>
 * </ul>
 *
 * <p>Raoh {@link Decoder}s validate and decode the incoming {@link JsonNode} body.
 * Success and error responses are returned as {@code Map<String, Object>} or
 * {@link ApiResponse}, letting {@code SerDesMiddleware} + {@code JsonBodyWriter}
 * handle serialization without reflection on domain classes.
 */
public class TodoWriteHandler {

    private static final Pattern ITEM_PATH = Pattern.compile("^/todos/([^/]+)$");

    private static final Decoder<JsonNode, String> CREATE_DECODER =
            JsonDecoders.field("title", JsonDecoders.string().trim().nonBlank());

    private record UpdateRequest(Optional<String> title, Optional<Boolean> done) {}

    private static final Decoder<JsonNode, UpdateRequest> UPDATE_DECODER =
            JsonDecoders.combine(
                    JsonDecoders.optionalField("title", JsonDecoders.string().trim().nonBlank()),
                    JsonDecoders.optionalField("done", JsonDecoders.bool())
            ).map(UpdateRequest::new);

    public Object handle(HttpRequest request, JsonNode body) {
        String method = request.getRequestMethod();
        String uri = request.getUri();

        if ("POST".equalsIgnoreCase(method) && "/todos".equals(uri)) {
            return create(body);
        }
        Matcher m = ITEM_PATH.matcher(uri);
        if (m.matches()) {
            String id = m.group(1);
            if ("PUT".equalsIgnoreCase(method)) return update(id, body);
            if ("DELETE".equalsIgnoreCase(method)) return delete(id);
        }
        return new ApiResponse(404, ProblemEncoder.notFound());
    }

    private Object create(JsonNode body) {
        return CREATE_DECODER.decode(body).fold(
                title -> TodoEncoder.TODO.encode(TodoStore.create(title)),
                issues -> new ApiResponse(400, ProblemEncoder.fromIssues(issues))
        );
    }

    private Object update(String id, JsonNode body) {
        return UPDATE_DECODER.decode(body).fold(
                req -> TodoStore.update(id, req.title().orElse(null), req.done().orElse(null))
                        .<Object>map(TodoEncoder.TODO::encode)
                        .orElseGet(() -> new ApiResponse(404, ProblemEncoder.notFound())),
                issues -> new ApiResponse(400, ProblemEncoder.fromIssues(issues))
        );
    }

    private Object delete(String id) {
        if (!TodoStore.delete(id)) return new ApiResponse(404, ProblemEncoder.notFound());
        HttpResponse res = HttpResponse.of("");
        res.setStatus(204);
        return res;
    }
}
