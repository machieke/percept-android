FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl bzip2 ffmpeg build-essential \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    sherpa-onnx==1.13.3 \
    fastapi==0.115.6 \
    "uvicorn[standard]==0.34.0" \
    numpy==2.2.6 \
    insightface==0.7.3 \
    onnxruntime==1.20.1 \
    opencv-python-headless==4.10.0.84

WORKDIR /app
COPY app.py entrypoint.sh /app/
RUN chmod +x /app/entrypoint.sh

EXPOSE 8000
ENTRYPOINT ["/app/entrypoint.sh"]
