FROM python:3.11-slim

ENV PIP_INDEX_URL=https://nexus.icomb.place/repository/pypi-proxy/simple/ \
    PIP_TRUSTED_HOST=nexus.icomb.place \
    DEBIAN_FRONTEND=noninteractive

WORKDIR /app

# Layer 1: Install Python dependencies. No GPU/model stack needed any more —
# translation is a plain HTTP call to an OpenAI-compatible chat endpoint.
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir \
        "httpx>=0.27" \
        "httpx-sse>=0.4" \
        "fastapi>=0.115" \
        "uvicorn>=0.32"

# Layer 2: Install shared worker library
COPY worker-common/ /tmp/worker-common/
RUN pip install --no-cache-dir /tmp/worker-common/ && rm -rf /tmp/worker-common/

# Layer 3: App source — only this rebuilds on code changes
COPY translate-worker/pyproject.toml ./
COPY translate-worker/src/ src/
RUN pip install --no-cache-dir -e .

EXPOSE 8001

CMD ["python3", "-m", "translate_worker.main"]
