FROM alpine:3.20
ARG PB_VERSION=0.40.6
# TARGETARCH is set by Docker (amd64 or arm64); the download is checked against the release checksums.
ARG TARGETARCH
RUN set -e; ARCH="${TARGETARCH:-amd64}"; ZIP="pocketbase_${PB_VERSION}_linux_${ARCH}.zip"; \
    URL="https://github.com/pocketbase/pocketbase/releases/download/v${PB_VERSION}"; \
    cd /tmp && wget -q "$URL/$ZIP" "$URL/checksums.txt" \
 && grep " $ZIP\$" checksums.txt | sha256sum -c -s - \
 && unzip "$ZIP" pocketbase -d /pb && rm "$ZIP" checksums.txt && chmod +x /pb/pocketbase
COPY pb_migrations /pb/pb_migrations
COPY pb_hooks /pb/pb_hooks
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s CMD wget -qO- http://127.0.0.1:8090/api/health >/dev/null || exit 1
CMD ["/pb/pocketbase", "serve", "--http=0.0.0.0:8090", "--dir=/pb/pb_data", "--migrationsDir=/pb/pb_migrations", "--hooksDir=/pb/pb_hooks"]
