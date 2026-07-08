FROM python:3.11-slim

RUN pip install --no-cache-dir \
    fastapi==0.115.6 \
    "uvicorn[standard]==0.34.0"

WORKDIR /app
COPY app.py /app/

EXPOSE 8131
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8131"]
