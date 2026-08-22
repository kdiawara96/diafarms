# Le jar est construit sur l'hôte par deploy.sh (./mvnw clean package) avant le build de l'image,
# comme atelierso_back sur le même serveur.
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/ml-*.jar app.jar
EXPOSE 9093
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
