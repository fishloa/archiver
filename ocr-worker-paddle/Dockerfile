FROM nvidia/cuda:12.4.1-cudnn-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-dev libgomp1 libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pyproject.toml ./
COPY src/ src/

RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir --timeout 120 --retries 5 paddlepaddle-gpu && \
    pip install --no-cache-dir -e .

# Pre-download German OCR models (use_gpu=False for build, runtime uses GPU)
RUN python3 -c "from paddleocr import PaddleOCR; PaddleOCR(use_angle_cls=True, lang='german', use_gpu=False, show_log=False)"

CMD ["python3", "-m", "ocr_worker.main"]
