# enkan-faas Project Instructions

## Language

All code, comments, commit messages, and documentation MUST be written in **English**.

## Overview

Function-as-a-Service adapters for the [Enkan](https://github.com/enkan/enkan) web framework.

This repository depends on Enkan core (`net.unit8.enkan:*`) but does not modify it.
If a missing API is discovered, file a separate issue/PR against `enkan/enkan` and
block the affected `enkan-faas` module on that release.

Maven multi-module project (`enkan-faas-parent` version `0.1.0-SNAPSHOT`).

## Module Structure

| Module | Role |
| ------ | ---- |
| `enkan-component-faas` | Vendor-neutral core (`RequestAdapter`, `ResponseAdapter`, `FunctionInvoker`) |
| `enkan-component-faas-aws` | AWS Lambda adapters (API Gateway HTTP API v2, Function URL) |
| `enkan-component-faas-gcp` | GCP Cloud Run / Cloud Functions adapters (Phase 2) |
| `enkan-component-faas-azure` | Azure Functions adapters (Phase 3) |
| `examples/todo-multifunction` | Multi-Function example with two AWS Lambdas in one SAM stack |

## Build and Test

```sh
# Full build
mvn verify

# Specific module only
mvn verify -pl enkan-component-faas-aws -am
```

## Key Architectural Decisions

### `RequestAdapter` takes a `Supplier<HttpRequest>`

The supplier is `webApp::createRequest`, which returns a **pre-mixed** `HttpRequest`
with cookie/session/flash/params mixin interfaces already attached. Adapters MUST
call `requestFactory.get()` instead of `new DefaultHttpRequest()` so downstream
middleware works correctly.

### `FunctionInvoker.boot()` always calls `system.registerCrac()`

`registerCrac()` is a documented no-op on non-CRaC JVMs (absorbed by the `org.crac`
portability shim), so non-SnapStart deployments pay zero cost. This means the same
handler code works on both AWS SnapStart and non-SnapStart runtimes without any
conditional logic.

### `ResponseAdapter` for AWS/Azure, `StreamingResponseAdapter` for GCP

GCP's `HttpFunction.service()` writes to an `OutputStream` sink and returns `void`,
which doesn't fit a value-returning `ResponseAdapter`. We introduce a separate
`StreamingResponseAdapter<R>` interface and a parallel `StreamingFunctionInvoker`
to handle this honestly. AWS and Azure keep the clean value-returning shape.

### Per-Function Maven module pattern

Each Function in the example (`todo-read`, `todo-write`) is its own Maven module
with its own `pom.xml` and Maven Shade Plugin configuration. This produces
independent shaded JARs that contain only the classes that Function actually uses.
Shared domain code lives in `examples/todo-multifunction/shared/`.

## Pull Requests

- Always target `main` as the base branch
- Create a feature branch → commit → `gh pr create --base main`
- Never commit directly to `main`

## Code Review Checklist

- Reuse existing Enkan public APIs (no edits to Enkan core)
- No reflection in hot paths — use Class File API or direct dispatch
- Adapters must be `static final` safe (no per-instance mutable state)
- Tests use fixture JSON deserialized via Jackson, not hand-built event POJOs (more
  realistic and resistant to vendor SDK changes)
