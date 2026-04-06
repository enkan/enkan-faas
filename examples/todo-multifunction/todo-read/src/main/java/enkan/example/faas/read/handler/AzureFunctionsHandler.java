package enkan.example.faas.read.handler;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import enkan.Application;
import enkan.component.ApplicationComponent;
import enkan.example.faas.read.TodoReadSystemFactory;
import enkan.faas.RequestAdapter;
import enkan.faas.azure.AzureHttpRequestAdapter;
import enkan.faas.azure.AzureResponseWriter;
import enkan.system.EnkanSystem;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Azure Functions entry point for the read-only TODO Function.
 *
 * <p>Azure's response builder is tied to the request ({@code createResponseBuilder}),
 * so we cannot use the value-returning {@code FunctionInvoker}. Instead, this
 * handler bootstraps {@link EnkanSystem} in a {@code static} block and calls
 * the request adapter, application, and {@link AzureResponseWriter} directly.
 */
public class AzureFunctionsHandler {

    private static final EnkanSystem SYSTEM;
    private static final Application<HttpRequest, HttpResponse> APPLICATION;
    private static final Supplier<HttpRequest> REQUEST_FACTORY;
    private static final RequestAdapter<HttpRequestMessage<Optional<String>>> REQUEST_ADAPTER =
            new AzureHttpRequestAdapter();

    static {
        SYSTEM = TodoReadSystemFactory.create();
        SYSTEM.start();
        SYSTEM.registerCrac();
        @SuppressWarnings("unchecked")
        ApplicationComponent<HttpRequest, HttpResponse> appComponent =
                SYSTEM.getComponent("app", ApplicationComponent.class);
        APPLICATION = appComponent.getApplication();
        REQUEST_FACTORY = ((WebApplication) APPLICATION)::createRequest;
    }

    @FunctionName("TodoRead")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req",
                    methods = {HttpMethod.GET},
                    authLevel = AuthorizationLevel.ANONYMOUS,
                    route = "{*path}")
            HttpRequestMessage<Optional<String>> request,
            ExecutionContext ctx) {

        HttpRequest enkanReq = REQUEST_ADAPTER.toHttpRequest(request, REQUEST_FACTORY);
        HttpResponse enkanRes;
        try {
            enkanRes = APPLICATION.handle(enkanReq);
        } catch (RuntimeException ex) {
            ctx.getLogger().severe("Unhandled exception: " + ex.getMessage());
            enkanRes = enkan.faas.FaasErrors.toInternalServerError(ex);
        }
        return AzureResponseWriter.write(request, enkanRes);
    }
}
