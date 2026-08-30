# Stage 1: Builder
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw

COPY src src

RUN ./mvnw clean package -DskipTests

# Stage 2: Runner
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /app/target/e-commerce-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
