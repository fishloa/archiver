FROM python:3.11-slim

ENV PIP_INDEX_URL=https://nexus.icomb.place/repository/pypi-proxy/simple/ \
    PIP_TRUSTED_HOST=nexus.icomb.place

WORKDIR /app

RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir \
        "openai>=1.0" \
        "httpx>=0.27" \
        "httpx-sse>=0.4"

# Install shared worker library
COPY worker-common/ /tmp/worker-common/
RUN pip install --no-cache-dir /tmp/worker-common/ && rm -rf /tmp/worker-common/

# App source
COPY embed-worker/pyproject.toml ./
COPY embed-worker/src/ src/
RUN pip install --no-cache-dir -e .

CMD ["python3", "-m", "embed_worker.main"]
