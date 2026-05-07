FROM eclipse-temurin:17-jre
WORKDIR /app

ARG JAR_FILE=target/gridgain-payments-demo-0.0.1-SNAPSHOT.jar

ENV JAVA_TOOL_OPTIONS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.math=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.time=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/jdk.internal.access=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.security.x509=ALL-UNNAMED -Dio.netty.tryReflectionSetAccessible=true -DIGNITE_NO_SHUTDOWN_HOOK=true"

COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
