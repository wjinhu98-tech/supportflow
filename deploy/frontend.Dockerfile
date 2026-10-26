FROM node:22.15.0-alpine3.21 AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src/ ./src/
COPY frontend/public/ ./public/
# A new output directory and --emptyOutDir false avoid Vite's recursive cleanup.
RUN ./node_modules/.bin/vue-tsc --noEmit \
    && ./node_modules/.bin/vite build --outDir /build-output --emptyOutDir false

FROM nginxinc/nginx-unprivileged:1.28.0-alpine-slim
COPY deploy/nginx.conf /etc/nginx/nginx.conf
COPY --from=build --chown=101:101 /build-output/ /usr/share/nginx/supportflow/
USER 101:101
EXPOSE 8080
# Bypass image entrypoint scripts that attempt to edit configuration at startup.
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
