package enkan.faas;

import java.lang.annotation.*;

/**
 * Marks an {@link enkan.config.ApplicationFactory} as the entry point of a FaaS function.
 *
 * <p>The {@code enkan-faas-maven-plugin} scans {@code target/classes/} for classes
 * carrying this annotation and:
 * <ol>
 *   <li>Generates a vendor-specific handler class (e.g. AWS {@code RequestHandler})
 *       that wires the factory into an {@link enkan.system.EnkanSystem} with the
 *       specified {@link #adapter()}.</li>
 *   <li>Uses the annotated class as the BFS root to compute the transitive dependency
 *       closure written to {@code target/{name}-shaded.jar}.</li>
 * </ol>
 *
 * <p>Apply this annotation to the {@link enkan.config.ApplicationFactory} implementation:
 *
 * <pre>{@code
 * @FaasFunction(name = "todo-read", adapter = ApiGatewayV2Adapter.class)
 * public class TodoReadApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {
 *     ...
 * }
 * }</pre>
 *
 * <p>{@link RetentionPolicy#CLASS} is used because the plugin reads the annotation at
 * build time via the Class File API; no runtime retention is needed.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
@Documented
public @interface FaasFunction {

    /**
     * The function name. Used as the stem of the output JAR:
     * {@code target/{name}-shaded.jar}, and as part of the generated handler
     * class name.
     */
    String name();

    /**
     * The {@link FaasAdapter} (or {@link StreamingFaasAdapter}) class to use
     * for translating vendor events into Enkan requests and back.
     *
     * <p>The plugin reads this class reference to determine:
     * <ul>
     *   <li>Which vendor-specific component to instantiate (e.g. {@code AwsLambdaComponent})</li>
     *   <li>Which vendor interface to implement on the generated handler class
     *       (e.g. {@code RequestHandler} for AWS)</li>
     * </ul>
     */
    Class<?> adapter();
}
