package enkan.example.faas.shared;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory TODO store. Static to share state across Lambda invocations
 * within a single warm container — for production use, swap for DynamoDB or
 * Cosmos via a separate component module.
 *
 * <p>Note: in a real multi-Function deployment, the read and write Lambdas
 * are different processes and would NOT share an in-process map. The
 * example uses static state to keep the demonstration self-contained; the
 * point of the example is to show per-Function bundling, not state sharing.
 * To make the example actually serve coherent reads/writes after deployment,
 * point both Lambdas at a shared backing store (DynamoDB, Cosmos, Firestore).
 */
public final class TodoStore {

    private static final ConcurrentMap<String, Todo> ITEMS = new ConcurrentHashMap<>();

    private TodoStore() {}

    public static Collection<Todo> findAll() {
        return ITEMS.values();
    }

    public static Optional<Todo> findById(String id) {
        return Optional.ofNullable(ITEMS.get(id));
    }

    public static Todo create(String title) {
        String id = UUID.randomUUID().toString();
        Todo todo = new Todo(id, title, false);
        ITEMS.put(id, todo);
        return todo;
    }

    public static Optional<Todo> update(String id, String title, Boolean done) {
        return findById(id).map(existing -> {
            Todo updated = existing;
            if (title != null) updated = updated.withTitle(title);
            if (done != null) updated = updated.withDone(done);
            ITEMS.put(id, updated);
            return updated;
        });
    }

    public static boolean delete(String id) {
        return ITEMS.remove(id) != null;
    }

    /** Visible for tests. */
    public static void clear() {
        ITEMS.clear();
    }
}
