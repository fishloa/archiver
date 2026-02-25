FROM python:3.10-slim

WORKDIR /app

# Install dependencies first for layer caching
COPY pyproject.toml ./
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir "httpx>=0.27" "httpx-sse>=0.4" "reportlab>=4.0" "Pillow>=10.0"

# Copy source and install package
COPY src/ src/
RUN pip install --no-cache-dir -e .

CMD ["python3", "-m", "pdf_worker.main"]
