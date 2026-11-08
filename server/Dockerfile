FROM denoland/deno:bin-2.5.6 AS deno
FROM python:3.13-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=deno /deno /usr/local/bin/deno
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt && useradd --create-home vget
COPY app.py .
USER vget
ENV VGET_HOST=0.0.0.0
EXPOSE 8080
CMD ["python", "app.py"]
