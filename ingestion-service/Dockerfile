FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY target/ingestion-service-1.0.0-jar-with-dependencies.jar app.jar
EXPOSE 7001
ENTRYPOINT ["java","-jar","app.jar"]
