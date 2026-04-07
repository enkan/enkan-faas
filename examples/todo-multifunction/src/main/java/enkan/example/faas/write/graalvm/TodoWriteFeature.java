package enkan.example.faas.write.graalvm;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * GraalVM {@link Feature} for the todo-write Lambda.
 *
 * <p>Same pattern as {@code TodoReadFeature} — registers the handler class,
 * the application factory, and the AWS Lambda events POJOs for reflection.
 */
public class TodoWriteFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        registerClassFully(access, "enkan.example.faas.write.handler.AwsLambdaHandler");
        registerClassFully(access, "enkan.example.faas.write.TodoWriteApplicationFactory");
        registerClassFully(access, "enkan.example.faas.write.TodoWriteSystemFactory");

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
