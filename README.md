# enkan-faas

[![License: EPL-2.0](https://img.shields.io/badge/License-EPL--2.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

Function-as-a-Service adapters for the [Enkan](https://github.com/enkan/enkan) web framework.

Deploy the same Enkan application to **AWS Lambda**, **Azure Functions**, or **Google Cloud Functions** — without a `WebServerComponent`. Write one `ApplicationFactory`, annotate it with `@FaasFunction`, run `mvn package`.

---

## Why Enkan fits FaaS better than other Java frameworks

### No scan, no proxy, no reflection on your domain classes

Enkan's DI is an explicit component graph declared in code:

```java
EnkanSystem.of(
    "app",    new ApplicationComponent<>(TodoReadApplicationFactory.class.getName()),
    "lambda", new AwsLambdaComponent(new ApiGatewayV2Adapter())
).relationships(ComponentRelationship.component("lambda").using("app"));
```

There is no classpath scanning, no annotation processor, no runtime proxy generation. The component graph is the configuration. This means:

- **GraalVM Native Image**: reflection surface is minimal and fully enumerable. Cold starts of 80–200 ms are achievable without heroic configuration.
- **SnapStart (AWS)**: `EnkanSystem.start()` in a `static` block runs before the checkpoint. Warm restore skips the init phase entirely.

### `Application.handle` is a pure function

```text
HttpRequest → middleware chain → HttpResponse
```

No Servlet API, no thread-local context, no mutable server state. The same `ApplicationFactory` runs on any platform adapter with no changes.

### Per-Function bundling — one repository, N independent Lambda JARs

This is the structural advantage that Spring Cloud Function and Quarkus Funqy do not offer.

Annotate each `ApplicationFactory` with `@FaasFunction`:

```java
@FaasFunction(name = "todo-read", adapter = ApiGatewayV2Adapter.class)
public class TodoReadApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> { ... }

@FaasFunction(name = "todo-write", adapter = ApiGatewayV2Adapter.class)
public class TodoWriteApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> { ... }
```

Run `mvn package`. The `enkan-faas-maven-plugin` uses the Java Class File API to trace the transitive dependency closure from each annotated class and produces:

```text
target/
  todo-read-shaded.jar    ← contains only classes reachable from TodoReadApplicationFactory
  todo-write-shaded.jar   ← contains only classes reachable from TodoWriteApplicationFactory
```

The read JAR does not contain write-side classes, and vice versa. Each Function gets the minimum possible footprint — smaller cold start, smaller attack surface, tighter IAM scope.

Why this is structurally hard in other frameworks:

| Feature | enkan-faas | Spring Cloud Function | Quarkus Funqy |
| --- | --- | --- | --- |
| Per-Function bundling in one module | **Yes** | No (N modules, N builds) | No (single deployment unit) |
| Reflection-free DI | Yes | No (proxy-heavy) | Compile-time only |
| Servlet API in request path | No | Yes (MVC path) | No |
| Explicit middleware chain | Yes | No | No |

### Raoh decoders — validation without reflection

Handler methods receive `JsonNode` and decode it with [Raoh](https://github.com/kawasima/raoh) `Decoder`s:

```java
private static final Decoder<JsonNode, String> CREATE_DECODER =
        JsonDecoders.field("title", JsonDecoders.string().trim().nonBlank());

public Object handle(HttpRequest request, JsonNode body) {
    return CREATE_DECODER.decode(body).fold(
            title -> TodoEncoder.TODO.encode(TodoStore.create(title)),   // Ok
            issues -> new ApiResponse(400, ProblemEncoder.fromIssues(issues)) // Err
    );
}
```

Domain objects are encoded to `Map<String, Object>` by Raoh `Encoder`s — no Jackson reflection on your classes. `SerDesMiddleware` serializes the map to JSON via `JsonBodyWriter`. The result: **zero reflection metadata needed for domain objects in `reflect-config.json`**.

---

## Modules

| Module | Purpose |
| --- | --- |
| `enkan-component-faas` | Vendor-neutral core: `FaasAdapter`, `StreamingFaasAdapter`, `FaasRoutingMiddleware`, `@FaasFunction` |
| `enkan-component-faas-aws` | AWS Lambda: `ApiGatewayV2Adapter`, `ApiGatewayRestAdapter`, `AwsLambdaComponent` |
| `enkan-component-faas-gcp` | GCP Cloud Functions: `GcpHttpAdapter`, `GcpFunctionsComponent` (streaming) |
| `enkan-component-faas-azure` | Azure Functions: `AzureHttpAdapter`, `AzureFunctionsComponent` |
| `enkan-faas-maven-plugin` | BFS closure + shaded JAR generation per `@FaasFunction` |
| `examples/todo-multifunction` | Full example: two AWS Lambda Functions in one Maven module |

---

## Quick start (AWS Lambda)

### 1. Add dependencies

```xml
<dependency>
  <groupId>net.unit8.enkan.faas</groupId>
  <artifactId>enkan-component-faas-aws</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>net.unit8.raoh</groupId>
  <artifactId>raoh-json</artifactId>
  <version>0.5.0</version>
</dependency>
```

Add the plugin to your build:

```xml
<plugin>
  <groupId>net.unit8.enkan.faas</groupId>
  <artifactId>enkan-faas-maven-plugin</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <executions>
    <execution>
      <goals><goal>bundle</goal></goals>
    </execution>
  </executions>
</plugin>
```

### 2. Write an ApplicationFactory

```java
@FaasFunction(name = "hello", adapter = ApiGatewayV2Adapter.class)
public class HelloApplicationFactory implements ApplicationFactory<HttpRequest, HttpResponse> {

    @Override
    public Application<HttpRequest, HttpResponse> create(ComponentInjector injector) {
        ObjectMapper mapper = JsonMapper.builder().build();

        WebApplication app = new WebApplication();
        app.use(new ContentTypeMiddleware());
        app.use(new ParamsMiddleware());
        app.use(new FaasRoutingMiddleware(HelloHandler.class, "handle",
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
```

### 3. Write a handler

```java
public class HelloHandler {
    public Object handle(HttpRequest request, JsonNode body) {
        return Map.of("message", "Hello, world!");
    }
}
```

### 4. Build and deploy

```bash
mvn package
# → target/hello-shaded.jar
```

Deploy `hello-shaded.jar` to AWS Lambda with handler `enkan.faas.generated.HelloHandler`.

---

## Example: todo-multifunction

`examples/todo-multifunction` demonstrates two independent Functions — `todo-read` (GET) and `todo-write` (POST/PUT/DELETE) — in a single Maven module.

**Middleware chain:**

```text
HTTP event
  ↓ FaasRoutingMiddleware   — fixes controller/method (replaces RoutingMiddleware)
  ↓ ContentNegotiationMiddleware
  ↓ SerDesMiddleware         — deserializes body → JsonNode, serializes response Map → JSON
  ↓ ControllerInvokerMiddleware
  ↓ Handler#handle(HttpRequest, JsonNode)
      └── Raoh Decoder validates input
          Ok  → TodoEncoder.TODO.encode(domainObject)  → Map (no reflection)
          Err → ApiResponse(400, ProblemEncoder.fromIssues(issues))  (RFC 9457)
```

Integration tests verify that the shaded JARs are fully isolated:

```java
// The read JAR must not contain write-side classes
assertThat(jf.stream().anyMatch(e -> e.getName().contains("faas/write/")))
        .isFalse();
```

---

## GraalVM Native Image

Each platform module ships a `reflect-config.json` covering the vendor SDK event POJOs. Your domain classes require **no reflection registration** when you use Raoh `Encoder`s — `Map<String, Object>` serialization requires no per-class metadata.

```text
reflect-config.json registrations needed:
  - AWS event/response POJOs (provided by enkan-component-faas-aws)
  - Your ApplicationFactory and handler classes (provided by your GraalVM Feature)
  - Domain classes: none (Raoh Encoder uses method references, not reflection)
```

---

## Building

Requires Java 25. Depends on `enkan` `0.14.2-SNAPSHOT` — install Enkan core locally first:

```bash
git clone https://github.com/enkan/enkan
cd enkan && mvn install -DskipTests

git clone https://github.com/enkan/enkan-faas
cd enkan-faas && mvn verify
```

---

## License

Eclipse Public License 2.0. Same as Enkan core.
