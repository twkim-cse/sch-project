from fastapi import FastAPI

# AI 서버는 상태를 저장하지 않고 백엔드만 호출(응답)한다. (기능명세서 1장)
app = FastAPI(title="insurance-terms-explainer-ai")


@app.get("/ai/health")
def health():
    return {"status": "UP"}
