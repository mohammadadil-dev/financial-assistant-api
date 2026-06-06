FROM eclipse-temurin:21-jre
WORKDIR /app
COPY finance-bot.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]