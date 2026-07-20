FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "build/libs/app.jar"]
