package enkan.example.faas.write.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import enkan.example.faas.write.TodoWriteSystemFactory;
import enkan.faas.FunctionInvoker;
import enkan.faas.aws.ApiGatewayV2RequestAdapter;
import enkan.faas.aws.ApiGatewayV2ResponseAdapter;

/**
 * AWS Lambda entry point for the write side of the TODO API.
 *
 * <p>Independent from {@code todo-read.AwsLambdaHandler}: this class lives in
 * a different Maven module and produces a different shaded JAR. The two
 * Lambdas share state only via {@code TodoStore} (in production, replace with
 * a real backing store such as DynamoDB).
 */
public class AwsLambdaHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final FunctionInvoker<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> INVOKER =
            FunctionInvoker.boot(
                    TodoWriteSystemFactory::create,
                    "app",
                    new ApiGatewayV2RequestAdapter(),
                    new ApiGatewayV2ResponseAdapter());

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx) {
        return INVOKER.invoke(event);
    }
}
