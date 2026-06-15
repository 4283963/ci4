FROM maven:3.8.6-openjdk-11-slim AS builder

WORKDIR /app
COPY pom.xml ./
COPY cold-common/pom.xml ./cold-common/
COPY cold-gateway/pom.xml ./cold-gateway/

RUN cd /app && mvn -N install
RUN cd /app/cold-common && mvn dependency:go-offline -B || true

COPY cold-common/src ./cold-common/src
RUN cd /app/cold-common && mvn clean install -DskipTests -B

WORKDIR /app/cold-gateway
RUN mvn dependency:go-offline -B || true
COPY cold-gateway/src ./src
RUN mvn clean package -DskipTests -B

FROM openjdk:11-jre-slim

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

WORKDIR /app
COPY --from=builder /app/cold-gateway/target/cold-gateway.jar ./app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
