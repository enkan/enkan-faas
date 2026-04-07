package enkan.example.faas.read.graalvm;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM {@link Feature} for the todo-read Lambda.
 *
 * <p>Registers the reflection metadata the native image build needs:
 * <ul>
 *   <li>The AWS Lambda handler class ({@link enkan.example.faas.read.handler.AwsLambdaHandler})
 *       and its no-arg constructor — the Lambda Runtime Interface Client loads
 *       the handler class from the {@code _HANDLER} environment variable at
 *       runtime and invokes {@code handleRequest} via reflection.</li>
 *   <li>The {@link enkan.example.faas.read.TodoReadApplicationFactory} —
 *       Enkan's {@code ApplicationComponent} loads the factory class by name
 *       via {@code Class.forName + newInstance}.</li>
 *   <li>The AWS Lambda events POJOs — the Lambda runtime uses Jackson
 *       databind to deserialize {@code APIGatewayV2HTTPEvent} and to serialize
 *       {@code APIGatewayV2HTTPResponse}, which needs reflective access to
 *       all fields and accessors.</li>
 * </ul>
 *
 * <p>Activated via
 * {@code META-INF/native-image/net.unit8.enkan.faas/todo-read/native-image.properties}.
 */
public class TodoReadFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        registerClassFully(access, "enkan.example.faas.read.handler.AwsLambdaHandler");
        registerClassFully(access, "enkan.example.faas.read.TodoReadApplicationFactory");
        registerClassFully(access, "enkan.example.faas.read.TodoReadSystemFactory");

        // AWS Lambda Java events POJOs deserialized by Jackson inside the
        // Lambda Runtime Interface Client. Registering the top-level event
        // class plus its nested RequestContext types is sufficient because
        // GraalVM traces field types from there.
        registerClassFully(access, "com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent");
        registerClassFully(access, "com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent$RequestContext");
        registerClassFully(access, "com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent$RequestContext$Http");
        registerClassFully(access, "com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent$RequestContext$Authorizer");
        registerClassFully(access, "com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse");
    }

    private static void registerClassFully(BeforeAnalysisAccess access, String className) {
        Class<?> clazz = access.findClassByName(className);
        if (clazz == null) return;
        RuntimeReflection.register(clazz);
        RuntimeReflection.registerAllConstructors(clazz);
        RuntimeReflection.registerAllFields(clazz);
        RuntimeReflection.registerAllMethods(clazz);
    }
}
