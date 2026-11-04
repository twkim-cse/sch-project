# 보험 약관 AI 서버

FastAPI / google-genai 기반 classify, extract, explain 구현입니다.
실제 테스트 이력과 제한 사항은 PROGRESS.md 및 README_real_policy.md를 참고하세요.

## 실행

ai 폴더에서 Python 3.12 이상 환경을 사용합니다.

```powershell
python -m pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8001
```

`GEMINI_API_KEY`, `GEMINI_MODEL`을 환경변수 또는 로컬 `.env`에 설정하세요.
검증에 사용한 모델 설정은 `gemini-3.5-flash-lite`입니다.
키와 `.env`는 저장소에 커밋하지 않습니다.
`/health`와 기존 모노레포 호환용 `/ai/health`를 제공합니다.
Docker는 기존 `app.main:app` 진입점을 유지하며 실제 구현을 로드합니다.

## 오프라인 검증

```powershell
python -m unittest -q test_explain test_real_policy_mock test_contract_audit
```

테스트는 dotenv와 Gemini 호출을 모의 처리합니다. 실제 API 테스트는 자동으로 실행되지 않습니다.

```powershell
# 입력 검사만 수행
python test_real_policy.py --data test_data/real_policy.json

# 명시적으로 실제 Gemini 호출; 위 실행 포트에 맞춤
python test_real_policy.py --server http://127.0.0.1:8001 --data test_data/real_policy.json --api explain --run --timeout 600
```

실제 약관 데이터는 6개 대표 조항 발췌이며 전체 약관 분석이 아닙니다.
실행 결과와 캐시 파일은 커밋 대상에서 제외합니다.
로컬 원본 requirements.txt의 python-dotenvx 오타는 게시용 파일에서 python-dotenv로 수정했습니다.
