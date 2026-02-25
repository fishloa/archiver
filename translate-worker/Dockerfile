## Stage 1: Download translation models (CPU-only torch)
FROM python:3.11-slim AS model-downloader

RUN pip install --no-cache-dir "torch>=2.6" --index-url https://download.pytorch.org/whl/cpu && \
    pip install --no-cache-dir "transformers>=4.40" sentencepiece

RUN python3 -c "\
from transformers import MarianMTModel, MarianTokenizer; \
MarianTokenizer.from_pretrained('Helsinki-NLP/opus-mt-de-en'); \
MarianMTModel.from_pretrained('Helsinki-NLP/opus-mt-de-en'); \
MarianTokenizer.from_pretrained('Helsinki-NLP/opus-mt-cs-en'); \
MarianMTModel.from_pretrained('Helsinki-NLP/opus-mt-cs-en'); \
"

# Make models world-readable for non-root execution
RUN chmod -R a+rX /root/.cache/huggingface/

## Stage 2: Final image
FROM python:3.11-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV HOME=/tmp

WORKDIR /app

# Layer 1: Install Python dependencies (CPU torch is fine for MarianMT)
RUN pip install --no-cache-dir --upgrade pip setuptools wheel && \
    pip install --no-cache-dir \
        "torch>=2.6" --index-url https://download.pytorch.org/whl/cpu && \
    pip install --no-cache-dir \
        "transformers>=4.40" \
        "sentencepiece>=0.2" \
        "langdetect>=1.0" \
        "httpx>=0.27" \
        "httpx-sse>=0.4"

# Layer 2: Copy pre-downloaded models (world-readable for non-root execution)
COPY --from=model-downloader /root/.cache/huggingface/ /root/.cache/huggingface/
RUN chmod -R a+rX /root/.cache/huggingface/ && chmod a+rx /root

# Create passwd entry for non-root uid so getpass.getuser() works
# (torch._dynamo calls this on import; without it uid 1012 crashes)
RUN echo "worker:x:1012:1012::/tmp:/bin/sh" >> /etc/passwd

# Layer 3: App source — only this rebuilds on code changes
COPY pyproject.toml ./
COPY src/ src/
RUN pip install --no-cache-dir -e .

CMD ["python3", "-m", "translate_worker.main"]
