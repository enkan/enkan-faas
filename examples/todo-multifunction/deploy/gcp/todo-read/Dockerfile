# Cloud Run container for the todo-read Function (JVM mode).
#
# GCP Cloud Run does not require AWS-style Lambda runtime bootstrap; it just
# needs an HTTP server listening on $PORT. We run the Google Functions
# Framework invoker, which loads our GcpFunctionsHandler at startup and
# serves HTTP on $PORT.
#
# For native image deployment on Cloud Run, build a second main class that
# starts a com.sun.net.httpserver.HttpServer and delegates to the handler —
# then switch this base image to gcr.io/distroless/base-debian12 with the
# native binary ENTRYPOINT. That refinement is tracked as a Phase 5 item.
#
# Build context must be the repository root so Maven can see shared/ and
# todo-read/ side by side.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
# Install Maven. eclipse-temurin image does not ship Maven by default.
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
# Build ONLY the todo-read Maven module — per-Function bundling guarantee.
RUN mvn -pl examples/todo-multifunction/todo-read -am package -DskipTests

# Download the Google Functions Framework invoker separately. It is kept
# out of the Function's shaded JAR to keep that JAR small and
# cloud-agnostic.
FROM eclipse-temurin:25-jdk AS invoker
WORKDIR /tmp
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/* \
 && mvn -B dependency:copy \
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
