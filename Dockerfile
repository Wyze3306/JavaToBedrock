# Image du proxy JavaToBedrock : docker build -t javatobedrock .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon shadowJar

FROM eclipse-temurin:21-jre
RUN useradd --user-group --no-create-home --home-dir /data --shell /usr/sbin/nologin jtb \
    && mkdir /data && chown jtb:jtb /data
COPY --from=build /src/build/libs/JavaToBedrock.jar /app/JavaToBedrock.jar
USER jtb:jtb
WORKDIR /data
VOLUME /data
# Java (TCP) et serveur HTTP des packs de ressources convertis
EXPOSE 25565/tcp 8765/tcp
ENTRYPOINT ["java", "-XX:+UseZGC", "-Djtb.data=/data", "-jar", "/app/JavaToBedrock.jar"]
