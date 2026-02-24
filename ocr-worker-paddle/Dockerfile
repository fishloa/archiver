FROM nvidia/cuda:12.6.3-cudnn-runtime-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 python3-pip python3-venv libgomp1 libglib2.0-0 libgl1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pyproject.toml ./
COPY src/ src/

RUN python3 -m pip install --break-system-packages --no-cache-dir \
        paddlepaddle-gpu \
    && python3 -m pip install --break-system-packages --no-cache-dir -e .

# Pre-download OCR models on build so startup is fast
RUN python3 -c "from paddleocr import PaddleOCR; PaddleOCR(use_angle_cls=True, lang='latin', use_gpu=False, show_log=False)"

CMD ["python3", "-m", "ocr_worker.main"]
