FROM node:22-bookworm-slim AS build

WORKDIR /app

ENV PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_CHROME_SKIP_DOWNLOAD=true

COPY backend/package.json backend/package-lock.json ./backend/
COPY backend/tsconfig.json backend/tsconfig.build.json ./backend/
WORKDIR /app/backend
# better-sqlite3 13 loads its bundled prebuild, but npm still runs an implicit node-gyp rebuild
# for it, which needs Python. The only other production install script is puppeteer's browser
# download, which this image skips. backend/test/dockerImage.test.ts guards both assumptions.
RUN npm ci --ignore-scripts

COPY backend/src ./src
RUN npm run build \
    && npm prune --omit=dev

FROM node:22-bookworm-slim AS runtime

ENV NODE_ENV=production \
    PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
    PUPPETEER_SKIP_DOWNLOAD=true \
    PUPPETEER_CHROME_SKIP_DOWNLOAD=true

RUN apt-get update \
    && apt-get install -y --no-install-recommends chromium ca-certificates fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app/backend

COPY --from=build /app/backend/package.json /app/backend/package-lock.json ./
COPY --from=build /app/backend/node_modules ./node_modules
COPY --from=build /app/backend/dist ./dist

RUN mkdir -p data whatsapp-sessions

EXPOSE 18432

CMD ["node", "--import", "./dist/instrument.js", "./dist/server.js"]
