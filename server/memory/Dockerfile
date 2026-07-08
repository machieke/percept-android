FROM python:3.11-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

# The Python reference implementation is the storage/ingestion engine.
ARG REFERENCE_REF=c4838bb2eea56ef323bb2ca5754cba7d13f0467c
RUN git clone https://github.com/machieke/event-trace-memory /opt/event-trace-memory \
    && git -C /opt/event-trace-memory checkout "${REFERENCE_REF}" \
    && rm -rf /opt/event-trace-memory/.git

# opencv needs libGL/glib at runtime even for the headless wheel.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    fastapi==0.115.6 \
    "uvicorn[standard]==0.34.0" \
    numpy==2.2.6 \
    pillow==11.0.0 \
    opencv-python-headless==4.11.0.86

WORKDIR /app
COPY app.py enrich.py identity.py reason.py attribute.py vehicle.py /app/

EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
