# Cloud Run container for the todo-write Function (JVM mode).
# Mirror of todo-read/Dockerfile — see that file for the commentary.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/*
RUN mvn -pl examples/todo-multifunction/todo-write -am package -DskipTests

FROM eclipse-temurin:25-jdk AS invoker
WORKDIR /tmp
RUN apt-get update && apt-get install -y --no-install-recommends maven && rm -rf /var/lib/apt/lists/* \
 && mvn -B dependency:copy \
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
