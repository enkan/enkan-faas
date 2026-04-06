package enkan.example.faas.read.handler;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import enkan.example.faas.read.TodoReadSystemFactory;
import enkan.faas.StreamingFunctionInvoker;
import enkan.faas.gcp.GcpHttpRequestAdapter;
import enkan.faas.gcp.GcpHttpResponseAdapter;

/**
 * GCP Cloud Functions / Cloud Run entry point for the read-only TODO Function.
 *
 * <p>Uses {@link StreamingFunctionInvoker} because GCP's response API is an
 * output-stream sink, not a return value. The adapter pipes the response body
 * directly into {@code sink.getOutputStream()} for true streaming.
 */
public class GcpFunctionsHandler implements HttpFunction {

    private static final StreamingFunctionInvoker<HttpRequest, HttpResponse> INVOKER =
            StreamingFunctionInvoker.boot(
                    TodoReadSystemFactory::create,
                    "app",
                    new GcpHttpRequestAdapter(),
                    new GcpHttpResponseAdapter());

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        INVOKER.invoke(request, response);
    }
}
