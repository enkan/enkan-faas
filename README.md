# enkan-faas

[![License: EPL-2.0](https://img.shields.io/badge/License-EPL--2.0-blue.svg)](https://www.eclipse.org/legal/epl-2.0/)

Function-as-a-Service adapters for the [Enkan](https://github.com/enkan/enkan) web framework.

Deploy the same Enkan application to **AWS Lambda**, **Azure Functions**, **Google Cloud Run**, or any FaaS platform — without a `WebServerComponent`. Bring your `ApplicationFactory`, swap a 25-line handler class, ship.

## Why this exists

Enkan's `Application.handle(HttpRequest): HttpResponse` is a pure function with zero Servlet API dependency. The middleware chain, kotowari routing, and `HttpRequest` POJOs are all server-agnostic. That makes Enkan a near-perfect fit for serverless: there is nothing to strip out, and adapters only need to translate vendor-specific event types to and from `HttpRequest` / `HttpResponse`.

CRaC support is already built into `EnkanSystem` (`registerCrac()`), so AWS SnapStart works with no user code changes.

## The differentiating capability: per-Function bundling

This is the part Spring Cloud Function and Quarkus structurally cannot do.

In `enkan-faas`, **one repository can contain N independent Function deploy artifacts**, each bundling only the components and middleware it actually uses:

```
my-app/
├── shared/                    ← common domain layer (Maven module)
├── todo-read/                 ← Function 1: GET endpoints (independent JAR)
│   └── handler/AwsLambdaHandler.java
├── todo-write/                ← Function 2: POST/PUT/DELETE (independent JAR)
│   └── handler/AwsLambdaHandler.java
└── deploy/aws/template.yaml   ← single SAM stack, two Lambda resources
```

The `todo-read` Lambda artifact does not contain `todo-write` classes, and vice versa. Memory budget, IAM scope, and cold start can be tuned per Function. The `examples/todo-multifunction/` project demonstrates this end to end.

Why this is structurally hard elsewhere:

- **Spring Boot**: `@ConditionalOn*` auto-configuration assumes a single `ApplicationContext` per build unit. Splitting requires N Maven modules and N copies of the auto-config tax.
- **Quarkus**: Extension orchestration is build-wide; SPI auto-discovery scans the whole classpath. Function-level subsetting is not first-class.
- **Enkan**: `EnkanSystem.of(...)` is an explicit component graph. Each Function declares exactly what it needs. Reflection-free, scan-free.

## Modules

| Module | Status | Purpose |
|---|---|---|
| `enkan-component-faas` | Phase 1 | Vendor-neutral core (`RequestAdapter`, `ResponseAdapter`, `FunctionInvoker`) |
| `enkan-component-faas-aws` | Phase 1 | AWS Lambda adapters (API Gateway HTTP API v2 / Function URL) |
| `enkan-component-faas-gcp` | Phase 2 | GCP Cloud Run / Cloud Functions adapters |
| `enkan-component-faas-azure` | Phase 3 | Azure Functions adapters |
| `examples/todo-multifunction` | Phase 1 | Full multi-Function example with AWS SAM deployment |

## Quick start (AWS Lambda)

1. **Configure your Enkan application** as usual — `ApplicationFactory`, `SystemFactory`, controllers.
2. **Add the dependency** to your Maven module:

   ```xml
   <dependency>
     <groupId>net.unit8.enkan.faas</groupId>
     <artifactId>enkan-component-faas-aws</artifactId>
     <version>0.1.0-SNAPSHOT</version>
   </dependency>
   ```

3. **Write a handler class** (~25 lines):

   ```java
   public class AwsLambdaHandler
           implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

       private static final FunctionInvoker<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> INVOKER =
           FunctionInvoker.boot(
               MyAppSystemFactory::create,
               "app",
               new ApiGatewayV2RequestAdapter(),
               new ApiGatewayV2ResponseAdapter());

       @Override
       public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context ctx) {
           return INVOKER.invoke(event);
       }
   }
   ```

4. **Deploy** with AWS SAM (see `examples/todo-multifunction/deploy/aws/`).

The `static final` invoker boots the entire `EnkanSystem` in Lambda's init phase — exactly where AWS SnapStart takes its checkpoint. Cold restore is then a memory snapshot, not a cold JVM startup.

## Cold start expectations

| Cloud | Mode | Cold start (typical) |
|---|---|---|
| AWS Lambda | java25 + SnapStart | 150-400 ms |
| AWS Lambda | Native (provided.al2023) | 80-200 ms |
| Azure Functions | java21 managed runtime | 1-3 s |
| GCP Cloud Run | Native (distroless) | 150-300 ms |

## Comparison with other Java FaaS frameworks

| | enkan-faas | Spring Cloud Function | Quarkus Funqy | Micronaut Function |
|---|---|---|---|---|
| Same code → multi-cloud | Yes | Yes | Limited | Yes |
| Per-Function bundling in one repo | **Yes (first-class)** | No | No | Per-application |
| Reflection-free DI | Yes | No (proxy-heavy) | Compile-time | Compile-time |
| GraalVM Native cold start | ~80-200 ms | ~200-350 ms | ~150-300 ms | ~150-300 ms |
| SnapStart support | Yes (built-in CRaC) | Yes | Yes | Yes |
| Servlet API in the request path | No | Yes (MVC) | No | No |

## Building

Requires Java 25 (matches Enkan core). The new repo depends on `enkan` `0.14.2-SNAPSHOT` while it stabilizes — install Enkan core locally first, or pull the SNAPSHOT from Sonatype.

```bash
git clone https://github.com/enkan/enkan-faas.git
cd enkan-faas
mvn verify
```

## Status

Phase 1 (core + AWS) is the initial vertical slice. Phases 2-4 add streaming + GCP, Azure, and polish. See the project board.

## License

Eclipse Public License 2.0. Same as Enkan core.
