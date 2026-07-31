# ipda management plane — small FastAPI app beside the engine containers.
# Needs: /opt/ipda mounted (deployments live there) + the docker socket
# (to start/stop engine containers). See deploy/docker-compose.yml.
FROM python:3.12-slim
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app /srv/app
ENV IPDA_ROOT=/ipda
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
