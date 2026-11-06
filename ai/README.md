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
현재 팀 통합 테스트 모델은 `gemini-3.6-flash`입니다. 코드에 모델명을 고정하지 않으며,
실행 환경의 `GEMINI_MODEL`을 그대로 사용합니다. PowerShell에서는 다음과 같이 설정합니다.

```powershell
$env:GEMINI_MODEL="gemini-3.6-flash"
```

`GEMINI_API_KEY`는 각자 환경변수 또는 Git 제외된 `.env`에서 제공하세요.
Compose 실행 시에는 저장소 루트에서 해당 환경변수를 제공한 뒤
`docker compose up -d --build ai`로 재배포합니다. 환경변수 변경 후에는 프로세스/컨테이너를
다시 만들어야 합니다. `GET /health`의 `model`로 실제 적용값을 확인하세요.
모델 변경은 계정별 접근 권한·할당량을 보장하지 않습니다.
키와 `.env`는 저장소에 커밋하지 않습니다.
`/health`와 기존 모노레포 호환용 `/ai/health`를 제공합니다.
Docker는 기존 `app.main:app` 진입점을 유지하며 실제 구현을 로드합니다.

## 오프라인 검증

```powershell
python -m unittest -q test_explain test_real_policy_mock test_contract_audit test_extract_validation test_extract_batching test_extract_categories test_extract_refund test_extract_recheck test_extract_duty test_extract_recheck_instruction test_extract_accuracy test_refund_postvalidation test_extract_pipeline test_explain_stability
```

테스트는 dotenv와 Gemini 호출을 모의 처리합니다. 실제 API 테스트는 자동으로 실행되지 않습니다.

explain의 단계별 제한 시간, 240초 요청 예산, 503/504의 제한적 재시도와 알려진 한계는
[README_explain_stability.md](README_explain_stability.md)를 참고하세요. B안은 기본 OFF입니다.

```powershell
# 입력 검사만 수행
python test_real_policy.py --data test_data/real_policy.json

# 명시적으로 실제 Gemini 호출; 위 실행 포트에 맞춤
python test_real_policy.py --server http://127.0.0.1:8001 --data test_data/real_policy.json --api explain --run --timeout 600
```

실제 약관 데이터는 6개 대표 조항 발췌이며 전체 약관 분석이 아닙니다.
실행 결과와 캐시 파일은 커밋 대상에서 제외합니다.
작업 원본과 게시용 requirements.txt를 pyproject.toml의 기존 런타임 의존성과 맞췄습니다.
코드의 `from dotenv import load_dotenv`는 `python-dotenv`를 사용합니다.
실제 PDF 추출 도구 등 로컬 검증 전용 패키지는 런타임 의존성에 추가하지 않았습니다.

## 배포 파일과 설정

기존 `app.main:app` 진입점과 8001 포트를 유지합니다. 최신 `app.py`와 함께
`extract_planning.py`, `extract_recheck.py`, `refund_validation.py`가 필요합니다.
Dockerfile은 이 모듈을 명시적으로 복사하며 `.dockerignore`는 런타임 파일만 허용합니다.
키, .env, 실제 약관, 테스트 결과는 이미지에 복사하지 않습니다. 키는 런타임에만 전달하세요.

저장소 루트의 Compose 빌드 컨텍스트는 `./ai`입니다. Compose는 호스트에서 제공하는
`GEMINI_API_KEY`, `GEMINI_MODEL`을 AI 컨테이너로 전달합니다. 모델은 환경변수로 지정하고,
실제 API 키를 저장소에 작성하지 마세요. 예전 `AI_MODEL_API_KEY` 항목은 보존했지만
현재 AI 코드가 읽는 키 이름은 `GEMINI_API_KEY`입니다.

```powershell
# 저장소 루트에서 실행. 필요한 Gemini 환경변수를 런타임에 제공한 경우에만 실제 AI 사용 가능.
docker compose build ai
docker compose up -d ai
Invoke-RestMethod http://127.0.0.1:8001/health
```

키가 없더라도 서버 시작과 /health는 가능하며, AI 엔드포인트는 설정 오류를 반환합니다.
모델 설정을 비워두면 실제 AI 분석이 정상 작동한다고 가정하지 마세요.
`/ai/classify`, `/ai/extract`, `/ai/explain` JSON 계약은 유지합니다.

B안 재확인은 `extract_recheck.py`의 `ENABLED = False`가 기본입니다. 기존 실험에서는
테스트 프로세스에서만 ON으로 사용했습니다. 배치/재확인으로 요청 시간이 길어질 수 있으므로
백엔드와 프록시의 시간 제한을 별도로 맞추세요. 호출 제한은 프로세스 단위이며 여러 worker가
하나의 제한을 공유하지는 않습니다. 백엔드의 기본 `MOCK_DOCUMENTS=true`는 기존 설정 그대로입니다.

## 검증 범위

게시 디렉터리에서 전체 173개 오프라인 테스트와 자격증명 없는 서버 시작·상태·경로 검증을
수행합니다. 실제 Gemini 호출은 수행하지 않습니다. 실제 약관 데이터와 실행 결과는 게시하지
않습니다. 기존 실제 결과는 대표 6/6 성공 이력이 있으나 최신 후처리의 실제 재검증은 503으로
중단됐습니다. Mock 통과를 실제 정확도나 가용성 보장으로 해석하지 마세요.

현재 준비 환경에는 Docker CLI가 없어 Docker 빌드·컨테이너 실행은 미검증입니다.
로컬 테스트는 Python 3.10.7로 실행했습니다. Docker Python 3.12와 다르므로 위 명령을 Docker 지원
환경에서 확인해야 합니다. 새 배포 환경의 의존성 설치도 별도 확인이 필요합니다.
