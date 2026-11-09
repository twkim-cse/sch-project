# 1. React 프로젝트 빌드
FROM node:20-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./

RUN npm ci

COPY . .

ARG VITE_API_BASE_URL

RUN npm run build


# 2. 빌드 결과물 실행
FROM node:20-alpine

WORKDIR /app

RUN npm install -g serve@14.2.5

COPY --from=build /app/dist ./dist

CMD ["sh", "-c", "serve --single --listen ${PORT:-3000} dist"]