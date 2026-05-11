FROM maven:3.9.9-eclipse-temurin-17 AS ojdbc

RUN mvn -q dependency:copy \
    -Dartifact=com.oracle.database.jdbc:ojdbc11:23.26.1.0.0 \
    -DoutputDirectory=/opt/ojdbc

FROM quay.io/debezium/connect:3.4

USER root
COPY --from=ojdbc /opt/ojdbc/ojdbc11-23.26.1.0.0.jar /kafka/libs/ojdbc11.jar
USER kafka
