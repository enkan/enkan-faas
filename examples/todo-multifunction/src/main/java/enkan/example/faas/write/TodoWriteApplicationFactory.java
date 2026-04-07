package enkan.example.faas.write;

import enkan.Application;
import enkan.config.ApplicationFactory;
import enkan.example.faas.shared.jaxrs.JsonBodyReader;
import enkan.example.faas.shared.jaxrs.JsonBodyWriter;
import enkan.example.faas.write.handler.TodoWriteHandler;
import enkan.faas.FaasFunction;
import enkan.faas.FaasRoutingMiddleware;
import enkan.faas.aws.ApiGatewayV2Adapter;
import enkan.system.inject.ComponentInjector;
import enkan.web.application.WebApplication;
import enkan.web.data.HttpRequest;
import enkan.web.data.HttpResponse;
import enkan.web.middleware.ContentNegotiationMiddleware;
import enkan.web.middleware.ContentTypeMiddleware;
import enkan.web.middleware.ParamsMiddleware;
import kotowari.middleware.ControllerInvokerMiddleware;
import kotowari.middleware.SerDesMiddleware;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static enkan.util.BeanBuilder.builder;

/**
 * Write-side TODO API: POST /todos, PUT /todos/{id}, DELETE /todos/{id}.
 *
 * <p>Middleware chain:
 * <ol>
 *   <li>{@link FaasRoutingMiddleware} — fixes handler to {@link TodoWriteHandler#handle}</li>
 *   <li>{@link ContentNegotiationMiddleware} — resolves response media type</li>
 *   <li>{@link SerDesMiddleware} — deserializes body to {@link JsonNode}, serializes response</li>
 *   <li>{@link ControllerInvokerMiddleware} — invokes the handler method</li>
 * </ol>
 */
@FaasFunction(name = "todo-write", adapter = ApiGatewayV2Adapter.class)
public class TodoWriteApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {

    @Override
    public Application<HttpRequest, HttpResponse> create(ComponentInjector injector) {
        ObjectMapper mapper = JsonMapper.builder().build();

        WebApplication app = new WebApplication();
        app.use(new ContentTypeMiddleware());
        app.use(new ParamsMiddleware());
        app.use(new FaasRoutingMiddleware(TodoWriteHandler.class, "handle",
                HttpRequest.class, JsonNode.class));
        app.use(builder(new ContentNegotiationMiddleware())
                .set(ContentNegotiationMiddleware::setAllowedTypes, Set.of("application/json"))
                .build());
        app.use(builder(new SerDesMiddleware<>())
                .set(SerDesMiddleware::setBodyWriters, new JsonBodyWriter<>(mapper))
                .set(SerDesMiddleware::setBodyReaders, new JsonBodyReader<>(mapper))
                .build());
        app.use(new ControllerInvokerMiddleware<>(injector));
        return app;
    }
}
