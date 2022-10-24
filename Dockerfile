FROM clojure:temurin-11-lein-2.9.10-alpine as builder

RUN mkdir /app
WORKDIR /app
COPY . /app/
RUN lein uberjar

FROM gcr.io/distroless/java:11
COPY --from=builder /app/target/eduhub-rio-mapper.jar /eduhub-rio-mapper.jar

ENTRYPOINT ["java", "-jar", "/eduhub-rio-mapper.jar"]
