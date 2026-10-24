# 보험 약관 위험 조항 해설 서비스

모노레포 구성: `frontend/`(React+Vite+TS) · `backend/`(Spring Boot, Java 21, Gradle) · `ai/`(Python 3.12, FastAPI, uv)

API 명세는 `docs/보험약관_해설서비스_기능명세서_데이터명세.pdf`를 따릅니다. 프로젝트 규칙은 `CLAUDE.md` 참고.

## 1. docker-compose로 한 번에 실행 (권장)

```bash
cp .env.example .env   # 필요 시 AI_MODEL_API_KEY 등 채우기
docker compose up --build
```

- frontend: http://localhost:5173
- backend: http://localhost:8080/api/health
- ai: http://localhost:8001/ai/health

종료: `docker compose down`

## 2. docker 없이 각각 실행

### ai (Python 3.12 + FastAPI + uv)

```bash
cd ai
cp .env.example .env
uv sync
uv run uvicorn app.main:app --reload --port 8001
```

확인: http://localhost:8001/ai/health → `{"status": "UP"}`

### backend (Spring Boot, Java 21)

```bash
cd backend
# ai 서버 주소를 다르게 쓰려면 환경변수로 지정 (기본값: http://localhost:8001)
./gradlew bootRun
```

Windows(cmd/PowerShell)에서는 `gradlew.bat bootRun`

확인: http://localhost:8080/api/health → `{"backend":"UP","ai":"UP"}` (ai 서버가 떠 있어야 ai도 UP)

### frontend (React + Vite)

```bash
cd frontend
cp .env.example .env   # 기본값(PROXY_TARGET=http://localhost:8080) 그대로 사용 가능
npm install
npm run dev
```

확인: http://localhost:5173 접속 시 Backend/AI 상태가 표시됩니다. (Vite 프록시가 `/api` 요청을 백엔드로 전달)

## 참고

- 프론트는 백엔드 주소를 직접 호출하지 않고 `/api` 상대 경로만 사용합니다. 실제 대상 서버는 `frontend/vite.config.ts`의 프록시 설정(`PROXY_TARGET`)이 결정합니다.
- 백엔드의 `/api/health`는 자신의 상태와 함께 AI 서버의 `/ai/health`를 2초 타임아웃으로 호출한 결과를 합쳐 반환합니다. AI 서버가 응답하지 않으면 `ai: "DOWN"`으로 표시됩니다.

## 문서 API 가짜 응답 모드 (MOCK_DOCUMENTS)

실제 약관 PDF 없이 프론트 화면을 만들 때 쓰는 옵션입니다. `MOCK_DOCUMENTS=true`이면
`POST /api/documents`·`GET /api/documents/{id}`가 실제 PDF를 검사·분석하지 않고,
**어떤 PDF를 올려도** 명세서 4장 예시와 같은 모양의 고정 결과(상태 READY, 예시 특약
목록 `rider01: 암진단 특별약관` 등)를 바로 돌려줍니다.

- **docker-compose 기본값은 `true`** 입니다(`docker-compose.yml`). `.env`에서
  `MOCK_DOCUMENTS=false`로 바꾸면 실제 F-02 입력 검사·F-04 구조 분석을 거칩니다.
- docker 없이 `./gradlew bootRun`으로 백엔드만 띄울 때 기본값은 `false`(실제 분석)이며,
  가짜 응답을 쓰려면 환경변수로 켭니다:
  ```bash
  MOCK_DOCUMENTS=true ./gradlew bootRun
  ```
