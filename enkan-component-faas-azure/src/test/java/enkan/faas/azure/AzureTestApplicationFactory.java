package enkan.faas.azure;

import enkan.Application;
import enkan.Middleware;
import enkan.MiddlewareChain;
import enkan.config.ApplicationFactory;
import enkan.system.inject.ComponentInjector;
import enkan.web.application.WebApplication;
import enkan.web.collection.Headers;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;

public class AzureTestApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {

    @Override
    public Application<HttpRequest, HttpResponse> create(ComponentInjector injector) {
        WebApplication app = new WebApplication();
        app.use(new Middleware<HttpRequest, HttpResponse, HttpRequest, HttpResponse>() {
            @Override
            public <NNREQ, NNRES> HttpResponse handle(
                    HttpRequest req,
                    MiddlewareChain<HttpRequest, HttpResponse, NNREQ, NNRES> chain) {
                return switch (req.getUri()) {
                    case "/hello" -> {
                        HttpResponse res = HttpResponse.of("hello");
                        res.setStatus(200);
                        res.setContentType("text/plain");
                        yield res;
                    }
                    case "/teapot" -> {
                        HttpResponse res = HttpResponse.of("teapot");
                        res.setStatus(418);
                        yield res;
                    }
                    case "/headers" -> {
                        HttpResponse res = HttpResponse.of("");
                        res.setStatus(204);
                        Headers h = Headers.empty();
                        h.put("X-Request-Id", "abc");
                        h.put("Cache-Control", "no-store");
                        res.setHeaders(h);
                        yield res;
                    }
                    default -> {
                        HttpResponse res = HttpResponse.of("not found");
                        res.setStatus(404);
                        yield res;
                    }
                };
            }
        });
        return app;
    }
}
