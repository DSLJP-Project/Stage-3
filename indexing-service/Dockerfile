# Imagen base con JDK 21
FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY target/indexing-service-1.0.0-jar-with-dependencies.jar app.jar

EXPOSE 7002

ENTRYPOINT ["java", "-jar", "app.jar"]
