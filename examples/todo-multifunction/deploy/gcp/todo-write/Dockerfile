# Multi-stage build for the todo-write Cloud Run service.
# Build context is the repository root.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# Build only the todo-write Maven module — todo-read is NOT included in this
# image. Per-Function bundling: each Cloud Run service ships only what it
# actually serves.
RUN mvn -pl examples/todo-multifunction/todo-write -am package -DskipTests

FROM eclipse-temurin:25-jdk AS invoker
WORKDIR /tmp
RUN mvn -B dependency:copy \
    -Dartifact=com.google.cloud.functions.invoker:java-function-invoker:1.3.1 \
    -DoutputDirectory=/tmp

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/examples/todo-multifunction/todo-write/target/todo-write-*-shaded.jar /app/todo-write.jar
COPY --from=invoker /tmp/java-function-invoker-*.jar /app/invoker.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-cp", "/app/invoker.jar", \
    "com.google.cloud.functions.invoker.runner.Invoker", \
    "--classpath", "/app/todo-write.jar", \
    "--target", "enkan.example.faas.write.handler.GcpFunctionsHandler"]
