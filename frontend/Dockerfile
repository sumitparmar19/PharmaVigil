# Production image of the UI: builds the Vite bundle, then serves it with nginx, which also routes each /api
# path to the service that owns it (same origin, so no CORS and the token never leaves the site).
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
