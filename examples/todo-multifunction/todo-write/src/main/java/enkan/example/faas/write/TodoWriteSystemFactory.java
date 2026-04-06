package enkan.example.faas.write;

import enkan.component.ApplicationComponent;
import enkan.system.EnkanSystem;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

/**
 * Builds the {@link EnkanSystem} for the {@code todo-write} Lambda. Contains
 * only the {@link ApplicationComponent} — no Jetty/Undertow, no JDBC, no
 * connection pool. Bundles only the components this Function actually uses.
 */
public final class TodoWriteSystemFactory {

    private TodoWriteSystemFactory() {}

    public static EnkanSystem create() {
        return EnkanSystem.of(
                "app", new ApplicationComponent<HttpRequest, HttpResponse>(
                        TodoWriteApplicationFactory.class.getName())
        );
    }
}
