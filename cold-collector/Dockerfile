FROM maven:3.8.6-openjdk-11-slim AS builder

WORKDIR /app
COPY pom.xml ./
COPY cold-common/pom.xml ./cold-common/
COPY cold-collector/pom.xml ./cold-collector/

RUN cd /app && mvn -N install
RUN cd /app/cold-common && mvn dependency:go-offline -B || true

COPY cold-common/src ./cold-common/src
RUN cd /app/cold-common && mvn clean install -DskipTests -B

WORKDIR /app/cold-collector
RUN mvn dependency:go-offline -B || true
COPY cold-collector/src ./src
RUN mvn clean package -DskipTests -B

FROM openjdk:11-jre-slim

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
COPY --from=builder /app/cold-collector/target/cold-collector.jar ./app.jar

EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
