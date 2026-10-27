FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
RUN useradd --system --uid 1001 --create-home visualizer
COPY --from=build /build/target/java-code-visualizer-*.jar app.jar
USER visualizer
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/app.jar"]
