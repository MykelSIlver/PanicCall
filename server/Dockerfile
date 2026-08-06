FROM python:3.12-alpine
RUN pip install --no-cache-dir "websockets>=12"
WORKDIR /app
COPY relay_server.py .
# pairs.json is mounted, not baked in — tokens do not belong in an image
# /state is a SEPARATE writable mount from the read-only /config: pairs.json
# (tokens) stays immutable from the container's perspective, while the
# queued-message state (no secrets, just a transient "call me" nudge) gets
# the one legitimate write path this relay needs. See docker-compose.example.yml.
EXPOSE 8765
CMD ["python3", "relay_server.py", "--pairs", "/config/pairs.json", \
     "--pending-state", "/state/pending.json"]
