FROM oven/bun:1-alpine AS builder
WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile
COPY . .
RUN bun run build

FROM oven/bun:1-alpine

# The released version, stamped by CI from the git tag. Read at runtime through
# $env/dynamic/public, so the image does not have to be rebuilt to correct it.
ARG APP_VERSION=dev
ENV PUBLIC_APP_VERSION=${APP_VERSION}

WORKDIR /app
COPY package.json bun.lock ./
RUN bun install --frozen-lockfile --production
COPY --from=builder /app/build ./build
EXPOSE 3000
ENV NODE_ENV=production
# adapter-node output, served by bun — verified serving 200 under the bun runtime
CMD ["bun", "build/index.js"]
