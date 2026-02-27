FROM python:3.10-slim

ENV PIP_INDEX_URL=https://nexus.icomb.place/repository/pypi-proxy/simple/ \
    PIP_TRUSTED_HOST=nexus.icomb.place

WORKDIR /app

# Install dependencies first for layer caching
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir "httpx>=0.27" "httpx-sse>=0.4" "reportlab>=4.0" "Pillow>=10.0"

# Create passwd entry for non-root uid so getpass.getuser() works
RUN echo "worker:x:1012:1012::/tmp:/bin/sh" >> /etc/passwd

# Install shared worker library
COPY worker-common/ /tmp/worker-common/
RUN pip install --no-cache-dir /tmp/worker-common/ && rm -rf /tmp/worker-common/

# Copy source and install package
COPY pdf-worker/pyproject.toml ./
COPY pdf-worker/src/ src/
RUN pip install --no-cache-dir -e .

CMD ["python3", "-m", "pdf_worker.main"]
