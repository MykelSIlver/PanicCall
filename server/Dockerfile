FROM python:3.12-alpine
RUN pip install --no-cache-dir "websockets>=12"
WORKDIR /app
COPY relay_server.py .
# pairs.json wordt gemount, niet ingebakken — tokens horen niet in een image
EXPOSE 8765
CMD ["python3", "relay_server.py", "--pairs", "/config/pairs.json"]
