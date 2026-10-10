# Stage 1: build static files with Vite
FROM node:24-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build          # output goes to /app/dist

# Stage 2: serve the static files with nginx. build-images.yml excludes the stage "runtime"
# from the layer cache (no-cache-filters), so the upgrade below runs on every CI build.
FROM nginx:1.30-alpine AS runtime

# Pick up Alpine security fixes published since the base image was built
RUN apk upgrade --no-cache

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
