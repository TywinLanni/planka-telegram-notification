FROM bellsoft/liberica-openjdk-debian:11
LABEL authors="Tywin"

# Установите необходимые переменные среды для Gradle
ENV GRADLE_USER_HOME=/cache
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false"

# Копировать проект в контейнер
COPY . /app
WORKDIR /app

# Установите Gradle
RUN apt-get update && \
    apt-get install -y wget unzip && \
    wget https://services.gradle.org/distributions/gradle-7.5.1-bin.zip && \
    unzip gradle-7.5.1-bin.zip -d /opt && \
    rm gradle-7.5.1-bin.zip && \
    ln -s /opt/gradle-7.5.1/bin/gradle /usr/bin/gradle && \
    apt-get remove -y wget unzip && \
    apt-get autoremove -y && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Соберите и выполните приложение с использованием Gradle
RUN gradle clean build
CMD ["gradle", "run"]
