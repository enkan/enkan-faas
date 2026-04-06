package enkan.example.faas.write.handler;

import com.google.cloud.functions.HttpFunction;
import com.google.cloud.functions.HttpRequest;
import com.google.cloud.functions.HttpResponse;
import enkan.example.faas.write.TodoWriteSystemFactory;
import enkan.faas.StreamingFunctionInvoker;
import enkan.faas.gcp.GcpHttpRequestAdapter;
import enkan.faas.gcp.GcpHttpResponseAdapter;

/**
 * GCP Cloud Functions / Cloud Run entry point for the write side of the TODO API.
 *
 * <p>Independent from {@code todo-read.GcpFunctionsHandler} — different Maven
 * module, different container image.
 */
public class GcpFunctionsHandler implements HttpFunction {

    private static final StreamingFunctionInvoker<HttpRequest, HttpResponse> INVOKER =
            StreamingFunctionInvoker.boot(
                    TodoWriteSystemFactory::create,
                    "app",
                    new GcpHttpRequestAdapter(),
                    new GcpHttpResponseAdapter());

    @Override
    public void service(HttpRequest request, HttpResponse response) throws Exception {
        INVOKER.invoke(request, response);
    }
}
