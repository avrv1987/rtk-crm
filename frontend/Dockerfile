FROM node:24.21.0-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm install --global npm@12.0.2
RUN npm ci --ignore-scripts
COPY frontend/ ./
COPY backend/src/main/resources/static/openapi.yaml /backend/src/main/resources/static/openapi.yaml
RUN npm run build

FROM nginx:1.29.2-alpine
COPY --from=build /app/dist /usr/share/nginx/html
