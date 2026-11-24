# syntax=docker/dockerfile:1

# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# 国内构建可使用 Aliyun 镜像；境外网络可删除 -s 参数使用 Maven Central
COPY docker/settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -s /root/.m2/settings.xml -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -s /root/.m2/settings.xml -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/message-exchange-1.0.0.jar /app/app.jar
EXPOSE 8080
ENV SERVER_PORT=8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
