# Stage 1: build + repackage the fat‐JAR
FROM maven:3.8.8-eclipse-temurin-21 AS builder
WORKDIR /usr/src/app

COPY pom.xml .
COPY src ./src
COPY lib/fit.jar ./lib/fit.jar

# install fit.jar into local repo
RUN mvn install:install-file \
      -Dfile=lib/fit.jar \
      -DgroupId=com.garmin \
      -DartifactId=fit \
      -Dversion=21.117 \
      -Dpackaging=jar

# clean → compile → package → repackage
RUN mvn clean package spring-boot:repackage -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=builder /usr/src/app/target/fit-injector-1.0.0.jar ./app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
