FROM python:3.13-slim

ENV PIP_INDEX_URL=https://nexus.icomb.place/repository/pypi-proxy/simple/ \
    PIP_TRUSTED_HOST=nexus.icomb.place

WORKDIR /app

# Install system dependencies for Pillow
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libjpeg62-turbo-dev \
        zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

COPY worker-common/ /tmp/worker-common/
RUN pip install --no-cache-dir /tmp/worker-common/ && rm -rf /tmp/worker-common/

COPY scraper-arolsen/pyproject.toml ./
COPY scraper-arolsen/src/ src/

RUN pip install --no-cache-dir .

CMD ["python", "-m", "scraper_arolsen.main", "--search", "czernin"]
