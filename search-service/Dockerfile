# Imagen base con JDK 21
FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY target/search-service-1.0.0-jar-with-dependencies.jar app.jar

EXPOSE 7003

ENTRYPOINT ["java", "-jar", "app.jar"]
