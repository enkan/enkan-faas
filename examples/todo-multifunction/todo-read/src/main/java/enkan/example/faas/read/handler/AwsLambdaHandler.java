package enkan.example.faas.read.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.example.faas.read.TodoReadSystemFactory;
import enkan.faas.FunctionInvoker;
import enkan.faas.aws.ApiGatewayV2RequestAdapter;
import enkan.faas.aws.ApiGatewayV2ResponseAdapter;

/**
 * AWS Lambda entry point for the read-only TODO Function.
 *
 * <p>The {@link FunctionInvoker} is held in a {@code static final} field so the
 * entire Enkan boot runs in Lambda's init phase — exactly where AWS SnapStart
 * takes its checkpoint. Cold restore becomes a memory snapshot rather than a
 * fresh JVM startup.
 */
public class AwsLambdaHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final FunctionInvoker<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> INVOKER =
            FunctionInvoker.boot(
                    TodoReadSystemFactory::create,
                    "app",
                    new ApiGatewayV2RequestAdapter(),
                    new ApiGatewayV2ResponseAdapter());

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx) {
        return INVOKER.invoke(event);
    }
}
