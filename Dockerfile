FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --system erp && useradd --system --gid erp --no-create-home erp
COPY --from=build --chown=erp:erp /build/target/ms-erp-0.1.0-SNAPSHOT.jar /app/app.jar
USER erp
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
