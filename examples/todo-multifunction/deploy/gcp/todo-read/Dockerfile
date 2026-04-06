# Multi-stage build for the todo-read Cloud Run service.
# Build context is the repository root (see README for the gcloud run deploy
# --source invocation), so paths are resolved from there.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# Build only the todo-read Maven module — todo-write is NOT rebuilt or packaged
# into this image. This is the per-Function bundling guarantee.
RUN mvn -pl examples/todo-multifunction/todo-read -am package -DskipTests

# Download the Google Functions Framework invoker JAR (separate from the
# function-framework-api that the handler depends on).
FROM eclipse-temurin:25-jdk AS invoker
WORKDIR /tmp
RUN mvn -B dependency:copy \
    -Dartifact=com.google.cloud.functions.invoker:java-function-invoker:1.3.1 \
    -DoutputDirectory=/tmp

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/examples/todo-multifunction/todo-read/target/todo-read-*-shaded.jar /app/todo-read.jar
COPY --from=invoker /tmp/java-function-invoker-*.jar /app/invoker.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-cp", "/app/invoker.jar", \
    "com.google.cloud.functions.invoker.runner.Invoker", \
    "--classpath", "/app/todo-read.jar", \
    "--target", "enkan.example.faas.read.handler.GcpFunctionsHandler"]
