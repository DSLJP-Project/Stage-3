# Imagen base con JDK 21
FROM eclipse-temurin:21-jdk

# Directorio de trabajo dentro del contenedor
WORKDIR /app

# Copiamos el JAR ensamblado con dependencias
COPY target/benchmarks-1.0.0-jar-with-dependencies.jar app.jar

# Puerto expuesto por el servicio de benchmarks
EXPOSE 7004

# Ejecutar la aplicación
ENTRYPOINT ["java", "-jar", "app.jar"]
