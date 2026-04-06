# Azure Functions deployment (todo-read / todo-write)

Each Function is deployed as a separate Function App on Azure, sharing the
same underlying source tree but different Maven modules. As with AWS and
GCP, the `todo-read` package does NOT contain `todo-write` code, and vice
versa.

## Prerequisites

- [Azure CLI](https://learn.microsoft.com/en-us/cli/azure/install-azure-cli)
  signed in (`az login`)
- [Azure Functions Core Tools](https://learn.microsoft.com/en-us/azure/azure-functions/functions-run-local)
  v4 for local testing
- Java 25 + Maven 3.9+ on `PATH`

## Configure the maven plugin

Add an Azure profile to each Function's `pom.xml`. Example for `todo-read`:

```xml
<profile>
  <id>azure</id>
  <build>
    <plugins>
      <plugin>
        <groupId>com.microsoft.azure</groupId>
        <artifactId>azure-functions-maven-plugin</artifactId>
        <version>1.42.0</version>
        <configuration>
          <appName>enkan-todo-read-${USER}</appName>
          <resourceGroup>enkan-faas-rg</resourceGroup>
          <region>eastus</region>
          <runtime>
            <os>linux</os>
            <javaVersion>21</javaVersion>
          </runtime>
          <hostJson>${project.basedir}/../deploy/azure/todo-read/host.json</hostJson>
        </configuration>
      </plugin>
    </plugins>
  </build>
</profile>
```

(The equivalent for `todo-write` uses `appName>enkan-todo-write-${USER}` and
the matching `host.json` path. We keep these configurations out of the
committed pom so the example stays buildable without Azure tooling on the
PATH. Copy the block when you actually want to deploy.)

## Deploy

```sh
cd examples/todo-multifunction/todo-read
mvn -Pazure azure-functions:deploy
```

```sh
cd examples/todo-multifunction/todo-write
mvn -Pazure azure-functions:deploy
```

Two **separate Function Apps** are created on Azure. Each has its own
resource allocation, staging slots, and Application Insights workspace.

## Caveats

- **No native image path on Azure Functions itself.** Azure Functions does
  not provide a first-class native runtime. If you need ~150 ms cold starts
  on Azure, use Azure Container Apps with the GCP-style Dockerfile pattern
  (copy from `deploy/gcp/todo-read/Dockerfile` and adjust for Container Apps
  ingress).
- **Cold start on the Java managed runtime**: typically 1-3 seconds. Use
  Premium Plan with "Always-Ready instances" if you need to eliminate cold
  starts for a price.
- **Shared state**: the example's `TodoStore` is an in-memory singleton.
  For production, point both Function Apps at a shared backing store such
  as Azure Cosmos DB.
