FROM python:3.13-slim

ENV PIP_INDEX_URL=https://nexus.icomb.place/repository/pypi-proxy/simple/ \
    PIP_TRUSTED_HOST=nexus.icomb.place

WORKDIR /app

COPY worker-common/ /tmp/worker-common/
RUN pip install --no-cache-dir /tmp/worker-common/ && rm -rf /tmp/worker-common/

COPY scraper-barch/pyproject.toml ./
COPY scraper-barch/src/ src/

RUN pip install --no-cache-dir .

CMD ["python", "-m", "scraper_barch.main"]
