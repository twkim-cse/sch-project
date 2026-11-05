FROM python:3.12-slim
WORKDIR /app
RUN pip install --no-cache-dir uv
COPY pyproject.toml ./
COPY app ./app
COPY app.py ./app.py
COPY extract_planning.py extract_recheck.py refund_validation.py ./
RUN uv pip install --system .
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
