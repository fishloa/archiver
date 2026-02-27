FROM quay.io/oauth2-proxy/oauth2-proxy:latest AS proxy

FROM python:3.12-alpine

ENV PIP_INDEX_URL=https://nexus.icomb.place/repository/pypi-proxy/simple/ \
    PIP_TRUSTED_HOST=nexus.icomb.place

RUN pip install --no-cache-dir PyJWT cryptography
COPY --from=proxy /bin/oauth2-proxy /bin/oauth2-proxy
COPY oauth2-proxy-apple/apple-entrypoint.sh /usr/local/bin/apple-entrypoint.sh
RUN chmod +x /usr/local/bin/apple-entrypoint.sh

ENTRYPOINT ["/usr/local/bin/apple-entrypoint.sh"]
