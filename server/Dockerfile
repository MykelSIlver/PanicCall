FROM python:3.12-alpine
RUN pip install --no-cache-dir "websockets>=12"
WORKDIR /app
COPY relay_server.py .
# pairs.json is mounted, not baked in — tokens do not belong in an image
EXPOSE 8765
CMD ["python3", "relay_server.py", "--pairs", "/config/pairs.json"]
