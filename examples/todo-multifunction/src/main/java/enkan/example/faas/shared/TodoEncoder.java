package enkan.example.faas.shared;

import net.unit8.raoh.encode.Encoder;

import java.util.Map;

import static net.unit8.raoh.encode.MapEncoders.object;
import static net.unit8.raoh.encode.MapEncoders.property;
import static net.unit8.raoh.encode.ObjectEncoders.bool;
import static net.unit8.raoh.encode.ObjectEncoders.string;

/**
 * Raoh encoder for {@link Todo} → {@code Map<String, Object>}.
 *
 * <p>The {@code SerDesMiddleware} + {@code JsonBodyWriter} serializes the map to JSON,
 * so handlers can return plain maps rather than hand-building {@code HttpResponse} bodies.
 */
public final class TodoEncoder {

    public static final Encoder<Todo, Map<String, Object>> TODO =
            object(
                    property("id",    Todo::id,    string()),
                    property("title", Todo::title, string()),
                    property("done",  Todo::done,  bool())
            );

    private TodoEncoder() {}
}
