FROM oven/bun:1-alpine AS builder
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
RUN bun run build

FROM oven/bun:1-alpine
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production
COPY --from=builder /app/build ./build
EXPOSE 3000
ENV NODE_ENV=production
# adapter-node output, served by bun — verified serving 200 under the bun runtime
CMD ["bun", "build/index.js"]
