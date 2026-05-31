FROM node:22-alpine

WORKDIR /app

COPY backend/package*.json ./backend/

RUN cd backend && npm ci

COPY backend ./backend/

WORKDIR /app/backend

RUN npm run build && npm prune --omit=dev && cp -r src/db/schema.sql dist/db/schema.sql

EXPOSE 3000

CMD ["node", "dist/server.js"]
