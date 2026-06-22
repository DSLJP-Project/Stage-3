# Imagen base con JDK 21
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copiamos el JAR ensamblado con dependencias
COPY target/crawler-service-1.0.0-jar-with-dependencies.jar app.jar

# Puerto expuesto por el crawler
EXPOSE 7007

# Ejecutar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]
