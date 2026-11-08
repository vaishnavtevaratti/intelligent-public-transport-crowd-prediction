# Build: Render (or `docker build -t transport-backend backend`) uses the backend/ folder as context.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Fits Render's 512 MB free instance.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k"
EXPOSE 8080
# Render injects PORT; fall back to 8080 elsewhere.
CMD ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -jar app.jar"]
