FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/flashgate-0.1.0.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
