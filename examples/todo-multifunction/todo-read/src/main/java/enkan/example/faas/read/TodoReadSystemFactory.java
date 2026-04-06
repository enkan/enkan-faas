package enkan.example.faas.read;

import enkan.component.ApplicationComponent;
import enkan.system.EnkanSystem;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

/**
 * Builds the {@link EnkanSystem} for the {@code todo-read} Lambda. Contains
 * only the {@link ApplicationComponent} — no Jetty/Undertow, no JDBC, no
 * connection pool. The Function bundles only the components it actually uses.
 */
public final class TodoReadSystemFactory {

    private TodoReadSystemFactory() {}

    public static EnkanSystem create() {
        return EnkanSystem.of(
                "app", new ApplicationComponent<HttpRequest, HttpResponse>(
                        TodoReadApplicationFactory.class.getName())
        );
    }
}
