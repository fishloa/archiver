## Stage 1: Download OCR models using CPU-only PaddlePaddle (no CUDA needed)
FROM python:3.10-slim AS model-downloader

RUN apt-get update && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 libgomp1 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir paddlepaddle==3.3.0 "paddleocr>=3.4,<4"
RUN python3 -c "from paddleocr import PaddleOCR; PaddleOCR(use_textline_orientation=True, lang='german', device='cpu')"

## Stage 2: Final image with GPU support
FROM python:3.10-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        libgomp1 libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Layer 1: PaddlePaddle GPU (from their index, --extra-index so PyPI is still primary)
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir --timeout 120 --retries 5 \
        --extra-index-url https://www.paddlepaddle.org.cn/packages/stable/cu126/ \
        paddlepaddle-gpu==3.3.0 && \
    pip install --no-cache-dir "paddleocr>=3.4,<4" "httpx>=0.27" "httpx-sse>=0.4" "Pillow>=10.0"

# Layer 2: Copy pre-downloaded models from stage 1
COPY --from=model-downloader /root/.paddlex /root/.paddlex

# Layer 3: App source — only this rebuilds on code changes
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir -e .

CMD ["python3", "-m", "ocr_worker.main"]
