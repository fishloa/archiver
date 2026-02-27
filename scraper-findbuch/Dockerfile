FROM python:3.13-slim

WORKDIR /app

# Install system dependencies for Pillow
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        libjpeg62-turbo-dev \
        zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

COPY worker-common/ /tmp/worker-common/
RUN pip install --no-cache-dir /tmp/worker-common/ && rm -rf /tmp/worker-common/

COPY scraper-findbuch/pyproject.toml ./
COPY scraper-findbuch/src/ src/

RUN pip install --no-cache-dir .

CMD ["python", "-m", "scraper_findbuch.main"]
