FROM gradle:8.10.2-jdk17 AS build
WORKDIR /src
COPY . /src
RUN gradle --no-daemon installDist

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S relay && adduser -S -G relay relay
COPY --from=build /src/build/install/coop-relay /app
COPY --from=build /src/cloudflare-ips.txt /app/cloudflare-ips.txt
USER relay
ENV JAVA_OPTS="-Xmx96m"
ENV CLOUDFLARE_RANGES_FILE=/app/cloudflare-ips.txt
EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8081/healthz || exit 1
ENTRYPOINT ["/app/bin/coop-relay"]
