from fastapi import Body, FastAPI

# AI 서버는 상태를 저장하지 않고 백엔드만 호출(응답)한다. (기능명세서 1장)
app = FastAPI(title="insurance-terms-explainer-ai")


@app.get("/ai/health")
def health():
    return {"status": "UP"}


# 명세서 5장 백엔드 ⇄ AI API 3개. 실제 모델 호출 없이, 명세서 예시 JSON을
# 그대로 고정 값으로 돌려주는 가짜(mock) 구현이다. 필드명은 snake_case를 쓴다.

@app.post("/ai/classify")
def classify(payload: dict = Body(default={})):
    return {
        "doc_type": "TERMS",
        "product_name": "(무)○○암보험",
        "version_label": "2025년 4월 개정",
    }


@app.post("/ai/extract")
def extract(payload: dict = Body(default={})):
    return {
        "extractions": [
            {
                "check_item": 2,
                "clause_id": "rider01-art5-p2",
                "quote": "암보장개시일은 계약일부터 그 날을 포함하여 90일이 지난날의 다음날로 합니다.",
            }
        ]
    }


@app.post("/ai/explain")
def explain(payload: dict = Body(default={})):
    # 요청한 item마다 카드 하나를 반드시 돌려준다(명세서 5장). 내용은 고정 예시다.
    items = (payload or {}).get("items", [])
    cards = [
        {
            "item_id": item.get("item_id"),
            "title": "가입하고 90일 안에 암 진단을 받으면 보험금이 나오지 않습니다",
            "explanation": "암에 대한 보장은 계약한 날을 포함해 90일이 지난 다음 날부터 시작됩니다.",
            "review": "pass",
            "review_reason": None,
        }
        for item in items
    ]
    return {"cards": cards}
