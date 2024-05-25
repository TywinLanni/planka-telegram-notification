FROM bellsoft/liberica-openjdk-alpine:17 AS build

WORKDIR /bot
COPY . .
RUN ./gradlew jar

FROM bellsoft/liberica-openjdk-alpine:17.0.9-11

ARG jar_version

COPY --from=build /bot/build/libs/planka-telegram-notification-$jar_version.jar /usr/local/lib/planka-telegram-notification-jar-with-dependencies.jar
CMD ["java", "-jar", "/usr/local/lib/planka-telegram-notification-jar-with-dependencies.jar"]
LABEL authors="TywinLanni"