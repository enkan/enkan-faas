package enkan.faas;

import enkan.MiddlewareChain;
import enkan.annotation.Middleware;
import enkan.data.Routable;
import enkan.util.MixinUtils;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

import java.lang.reflect.Method;

/**
 * Replaces {@code RoutingMiddleware} for FaaS where each Function has exactly one handler.
 *
 * <p>Sets a fixed controller class and method on the request so that
 * {@code SerDesMiddleware} and {@code ControllerInvokerMiddleware} can resolve
 * the handler without a routing table.
 *
 * <p>Declares {@code name = "routing"} and {@code mixins = Routable.class} to satisfy
 * the dependency declared by {@code SerDesMiddleware}.
 */
@Middleware(name = "routing", mixins = Routable.class)
public class FaasRoutingMiddleware
        implements enkan.Middleware<HttpRequest, HttpResponse, HttpRequest, HttpResponse> {

    private final Class<?> controllerClass;
    private final Method controllerMethod;

    public FaasRoutingMiddleware(Class<?> controllerClass, String methodName,
                                 Class<?>... paramTypes) {
        this.controllerClass = controllerClass;
        try {
            this.controllerMethod = controllerClass.getMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException(
                    "Method not found: " + controllerClass.getName() + "#" + methodName, e);
        }
    }

    @Override
    public <NNREQ, NNRES> HttpResponse handle(
            HttpRequest request,
            MiddlewareChain<HttpRequest, HttpResponse, NNREQ, NNRES> chain) {
        request = MixinUtils.mixin(request, Routable.class);
        ((Routable) request).setControllerClass(controllerClass);
        ((Routable) request).setControllerMethod(controllerMethod);
        return chain.next(request);
    }
}
