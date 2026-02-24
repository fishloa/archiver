FROM nvidia/cuda:12.6.3-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-dev libgomp1 libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Layer 1: Heavy deps — cached unless versions change
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir --timeout 120 --retries 5 \
        paddlepaddle-gpu==3.3.0 -i https://www.paddlepaddle.org.cn/packages/stable/cu126/ && \
    pip install --no-cache-dir "paddleocr>=3.4,<4" "httpx>=0.27" "httpx-sse>=0.4" "Pillow>=10.0"

# Layer 2: Pre-download German OCR models — cached unless paddleocr changes
RUN python3 -c "from paddleocr import PaddleOCR; PaddleOCR(use_textline_orientation=True, lang='german', device='cpu')"

# Layer 3: App source — only this rebuilds on code changes
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir -e .

CMD ["python3", "-m", "ocr_worker.main"]
