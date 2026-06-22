# Imagen base con JDK 21
FROM eclipse-temurin:21-jdk

WORKDIR /app

# Copiamos el JAR ensamblado con dependencias
COPY target/microbenchmarks-1.0.0-jar-with-dependencies.jar app.jar

# Puerto expuesto por el servicio de microbenchmarks
EXPOSE 7005

# Ejecutar la aplicación de microbenchmarks
ENTRYPOINT ["java", "-cp", "app.jar", "es.ulpgc.searchengine.MicrobenchmarkApp"]
