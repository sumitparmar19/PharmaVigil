# Production image of the UI: builds the Vite bundle, then serves it with nginx, which also routes each /api
# path to the service that owns it (same origin, so no CORS and the token never leaves the site). nginx runs as
# its unprivileged user (uid 101), so the pod can require runAsNonRoot.
# The bundle is the same on every CPU, so it is built once on the build machine's own platform (fast multi-arch).
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
RUN sed -i -e '/^user /d' -e 's#^pid .*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf \
    && chown -R 101:101 /var/cache/nginx /etc/nginx/conf.d
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
