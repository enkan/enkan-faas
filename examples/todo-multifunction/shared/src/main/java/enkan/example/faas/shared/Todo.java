package enkan.example.faas.shared;

/**
 * A simple immutable record representing a TODO item.
 *
 * <p>Pure POJO — no framework annotations — so it can be serialized by any
 * JSON library and shared between independent Function modules.
 */
public record Todo(String id, String title, boolean done) {
    public Todo withTitle(String newTitle) {
        return new Todo(id, newTitle, done);
    }

    public Todo withDone(boolean newDone) {
        return new Todo(id, title, newDone);
    }
}
