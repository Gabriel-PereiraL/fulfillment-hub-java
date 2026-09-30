ARG BUILD_IMAGE=maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976
ARG RUNTIME_IMAGE=eclipse-temurin:25-jre@sha256:8da0490fa9a3c26867012019565948eef0ee69438f5c75ac28146967bae984b5
FROM ${BUILD_IMAGE} AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN mvn -B -ntp -pl ${MODULE} -am -DskipTests package

FROM ${RUNTIME_IMAGE}
ARG MODULE
RUN apt-get update \
    && apt-get upgrade --yes \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-1.0.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
