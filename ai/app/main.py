"""Monorepo entry point for the implemented insurance AI service."""
from pathlib import Path
import runpy

app = runpy.run_path(str(Path(__file__).resolve().parents[1] / "app.py"))["app"]


@app.get("/ai/health")
def compatibility_health():
    return {"status": "UP"}
