# todo-multifunction

A multi-Function Enkan example demonstrating per-Function bundling on AWS Lambda.

## What this shows

A single repository contains:

- `shared/` — common domain layer (`Todo`, `TodoStore`)
- `todo-read/` — independent Maven module producing the `todo-read` Lambda Function (GET endpoints)
- `todo-write/` — independent Maven module producing the `todo-write` Lambda Function (POST/PUT/DELETE)
- `deploy/aws/` — single SAM stack defining both Lambdas

The `todo-read` shaded JAR contains only read-side classes. The `todo-write`
shaded JAR contains only write-side classes. Neither contains the other's
controllers. **This is structurally impossible in Spring Boot or Quarkus** —
their build orchestration assumes a single application context per build unit.

## Verifying the per-Function bundling

After building, inspect the JARs:

```sh
mvn -pl examples/todo-multifunction -am package -DskipTests

unzip -l examples/todo-multifunction/todo-read/target/todo-read-*-shaded.jar \
  | grep -E "TodoRead|TodoWrite"

# Output:
#    750  enkan/example/faas/read/TodoReadSystemFactory.class
#   5076  enkan/example/faas/read/TodoReadApplicationFactory.class
#   2765  enkan/example/faas/read/TodoReadApplicationFactory$1.class
#
# (no TodoWrite* entries)
```

The corresponding integration test asserts the same thing in code
(`TodoReadIntegrationTest.todoWriteControllerIsNotOnTheClasspath`), so the
guarantee is enforced on every CI run.

## JAR sizes

| Function | Shaded JAR size |
|---|---|
| `todo-read` | ~2.1 MB |
| `todo-write` | ~2.1 MB |

For comparison, a typical Spring Boot 3 Lambda fat JAR is **30-50 MB**. The
`enkan-faas` approach is roughly **20× smaller per Function** because each
module bundles only its own dependencies — no auto-configuration, no
component scanning, no servlet container.

## Local testing (no AWS account needed)

```sh
mvn verify -pl examples/todo-multifunction -am
```

The integration tests boot a real `EnkanSystem` in-process, build real
`APIGatewayV2HTTPEvent` objects, and round-trip all 5 CRUD endpoints through
the actual `FunctionInvoker` — no cloud SDK network calls.

## Deploying to AWS

Requires the [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html),
the AWS CLI configured with credentials, and Java 25 + Maven on `PATH`.

```sh
cd examples/todo-multifunction/deploy/aws
sam build
sam deploy --guided
```

`sam build` invokes the per-Function `build-*` targets in `Makefile`, which
each run `mvn -am package` against only that Function's Maven module. The
result is two CloudFormation `AWS::Serverless::Function` resources, each with
its own JAR artifact, deployed in a single stack.

After `sam deploy` completes, the stack output `ApiUrl` is the public HTTPS
endpoint of the HTTP API:

```sh
# Create a TODO (routed to TodoWriteFunction)
curl -X POST $API_URL/todos \
  -H "content-type: application/json" \
  -d '{"title":"buy milk"}'

# List all TODOs (routed to TodoReadFunction — note: in-memory store, so the
# read Lambda may not see the write Lambda's data unless they share a backing
# store. See the note in TodoStore.java.)
curl $API_URL/todos
```

Verify in the AWS console that **two distinct Lambda functions** were created,
not one. Each has its own memory budget, IAM role, and CloudWatch log group.

## Cold start measurements

After deployment, exercise SnapStart by publishing a new version:

```sh
aws lambda publish-version --function-name enkan-todo-read
aws lambda publish-version --function-name enkan-todo-write
```

Then check the `Init Duration` field in CloudWatch Logs Insights for both
Functions. Expected:

| Mode | Init Duration | Total cold start |
|---|---|---|
| First invocation (no SnapStart) | ~2-4 s | ~2-5 s |
| SnapStart restore | ~80-150 ms | ~150-400 ms |

## Cleanup

```sh
sam delete --stack-name enkan-todo
```

## Deploying to GCP Cloud Run

Each Function has its own multi-stage `Dockerfile` under `deploy/gcp/`. Build
and deploy from the repo root:

```sh
cd /path/to/enkan-faas
gcloud run deploy enkan-todo-read \
    --source . \
    --dockerfile examples/todo-multifunction/deploy/gcp/todo-read/Dockerfile \
    --region us-central1 \
    --allow-unauthenticated

gcloud run deploy enkan-todo-write \
    --source . \
    --dockerfile examples/todo-multifunction/deploy/gcp/todo-write/Dockerfile \
    --region us-central1 \
    --allow-unauthenticated
```

Each `Dockerfile` builds ONLY its own Maven module, so the resulting Cloud
Run container image contains only that Function's code. Cold start
(non-native): 1-3 s. For sub-300 ms cold starts, migrate the Dockerfile to a
GraalVM native stage (Phase 5 work, out of scope for this example).

## Deploying to Azure Functions

See [deploy/azure/README.md](deploy/azure/README.md). Each Function is
deployed as its own Azure Function App with its own `host.json` under
`deploy/azure/todo-read/` and `deploy/azure/todo-write/`.

## Benchmarking cold start

After deployment, run the bench script to measure cold-start duration:

```sh
./deploy/aws/bench/measure-cold-start.sh 5
```

The script forces a cold start (via environment variable toggling), invokes
each Function once, and parses the `REPORT` line from CloudWatch Logs to
extract `Init Duration`, `Duration`, and `Billed Duration`. Run it once
before enabling SnapStart and once after — the Init Duration should drop
from ~2-4 s to ~150-400 ms.
