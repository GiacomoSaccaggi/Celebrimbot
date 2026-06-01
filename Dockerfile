FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache curl bash

WORKDIR /app
COPY build/libs/celebrimbot.jar /app/celebrimbot.jar

EXPOSE 16180

ENTRYPOINT ["java", "-jar", "/app/celebrimbot.jar"]
CMD ["serve", "--port", "16180"]
