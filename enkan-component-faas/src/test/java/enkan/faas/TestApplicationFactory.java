package enkan.faas;

import enkan.Application;
import enkan.Middleware;
import enkan.MiddlewareChain;
import enkan.config.ApplicationFactory;
import enkan.system.inject.ComponentInjector;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

/**
 * Tiny ApplicationFactory used by FunctionInvokerTest. Routes any GET request
 * to a 200 "ok" response and any POST to a 201 "created" response. The path
 * {@code /boom} throws to exercise the FaasErrors path.
 */
public class TestApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {
    @Override
    public Application<HttpRequest, HttpResponse> create(ComponentInjector injector) {
        WebApplication app = new WebApplication();
        app.use(new Middleware<HttpRequest, HttpResponse, HttpRequest, HttpResponse>() {
            @Override
            public <NNREQ, NNRES> HttpResponse handle(
                    HttpRequest req,
                    MiddlewareChain<HttpRequest, HttpResponse, NNREQ, NNRES> chain) {
                String uri = req.getUri();
                if ("/boom".equals(uri)) {
                    throw new RuntimeException("intentional");
                }
                String method = req.getRequestMethod();
                if ("POST".equalsIgnoreCase(method)) {
                    HttpResponse res = HttpResponse.of("created");
                    res.setStatus(201);
                    return res;
                }
                return HttpResponse.of("ok");
            }
        });
        return app;
    }
}
