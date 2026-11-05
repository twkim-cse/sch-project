# 진행 상황 — 2026-11-04

## 2026-11-05: 게시 디렉터리 배포 준비(미커밋)

- 작업 루트의 최신 app.py와 extract_planning/extract_recheck/refund_validation 모듈,
  회귀 테스트, 가상 fixture, 문서를 ai 디렉터리에 선별 반영함.
- Docker 진입점 app.main:app/8001 유지, 새 런타임 모듈 명시 복사 및 Docker 컨텍스트
  허용 목록 추가. 실제 약관·키·로그·결과는 복사/커밋 대상에서 제외함.
- requirements.txt는 기존 pyproject.toml 최소 버전과 일치. 원본의 python-dotenvx는
  실제 import에 맞는 python-dotenv로 수정. pyproject 자체의 버전 기준은 변경하지 않음.
- 기존 Compose의 타 서비스 설정은 유지하고 AI 서비스에 GEMINI_API_KEY/GEMINI_MODEL
  환경변수 전달만 추가함. 실제 .env 값은 읽거나 바꾸지 않음.
- 게시 디렉터리 전체 오프라인 테스트 173개 통과. 최신 모듈 import, 자격증명 없는
  네이티브 uvicorn 시작, /health 200, OpenAPI 세 POST 경로와 B안 OFF 확인.
- 로컬 Python 3.10.7에서 검증했으며 Docker의 Python 3.12 실행은 Docker CLI 부재로
  미검증. Docker 빌드·실행 성공이나 새 환경 의존성 설치 성공을 주장하지 않음.
- 실제 Gemini 호출 및 GitHub 커밋·푸시 없음. 최신 후처리 실제 회귀는 503으로 중단된
  상태이므로 오프라인 검증과 실제 정확도 검증을 구분함.

## 2026-11-05: extract 1번/2번 분류 지침 보완

### 후속: 6번 직접 관련성 지침

- EXTRACT_INSTRUCTION에 환급 계산·반환·지급·차감·청구권 소멸시효 포함 기준과
  직접 연결 없는 해지/효력상실/대출 제외 기준 추가. 연결된 지급·공제 항 중심 추출.
- 1~5번 기존 지침 및 처리 로직 변경 없음. 코드의 강제 번호 보정이나 의미 필터 없음.
- 새 가상 환급 사례와 회귀 테스트 11개 추가, 전체 104개 오프라인 테스트 통과.
- 실제 Gemini 분류 개선 여부는 미검증. 이번 작업에서 실제 호출·커밋·푸시 없음.

- 40개 실제 테스트는 다중 배치 전송·인용 검증 성공이나 기대 항목 대응 5/6.
  부담보 기간 원문이 1번으로만 반환되어 2번이 누락됨. 검증 제외는 아님.
- 생성 지침에서 미지급 사유(1)와 기간부 제한·감액·부담보(2)를 명확히 구분.
  원문 근거가 각각 명확하면 같은 조항의 복수 항목 허용. 번호 강제 보정 없음.
- 새 가상 분류 사례 7종과 복수 항목 중복 제거 테스트 추가. 전체 93개 통과.
- 모델 내부 원인이나 실제 정확도 향상은 미확정. 실제 Gemini 재호출 없음.
- 점수·배치·문맥 사본·재시도·인용 검증·classify/explain/F13·API 계약 유지.
- 실제 자료 및 .env 변경, 커밋/푸시 없음.

## 최신 추가: extract 무누락 후보 우선순위 및 배치

- 후속 보완: 배치 최종 실패 시 나머지 배치 즉시 중단 확인. 기본 분석 배정은 ID별
  정확히 1회 검사하고, 같은 조 경계 예외·참조 문맥 사본만 명시적 이유로 허용.
  예산상 문맥 사본을 생략하면 별도 플래그 기록. 최종 결과에서 중복 제거.
- 후속 보완 포함 오프라인 테스트 총 85개 통과. 실제 Gemini 호출 없음.

- app.py와 새 extract_planning.py: 요청 항목별 키워드 점수, 같은 조 문맥 그룹,
  25개/24,000바이트 예산 기반 순차 배치. 낮은 점수도 제외하지 않고 정확히 한 번 포함.
- 작은 입력은 예산 내 단일 호출 유지. 단일 조항 초과·40배치 초과는 사전 HTTP 413.
- 모든 배치 병합 후 기존 원문 검증·중복 제거. 일부 배치 실패는 전체 오류 반환.
- extract 생성 호출도 기존 explain 프로세스 호출 창을 공유. 429/503 최대 1회 재시도.
- 기존 61개 + 새 19개 = 80개 오프라인 테스트 통과. 실제 Gemini 호출 없음.
- KB/삼성 실제 자료, .env, 모델 설정 변경 없음. GitHub 커밋/푸시하지 않음.
- 자세한 예산·호출 수·남은 제약은 README_extract_batching.md 참고.
- 배포 시 새 모듈 포함 필요. .publish_repo/Docker 변경은 아직 수행하지 않음.

## 최신 추가: explain 호출 속도 제한 및 429 재시도

- explain 전용 프로세스 공용 rolling window: 최근 60초 최대 10회.
- 생성, 검토, 재생성, 429/503 재시도 및 실패한 시도를 모두 포함.
- 429: Retry-After 우선, 구조화된 RetryInfo 다음, 기본 60초 대기 후 1회 재시도.
- 503: 기존 2초 대기 유지. 한 호출의 총 시도는 오류 종류와 관계없이 최대 2회.
- 기존 API 계약, classify/extract, 공유 클라이언트와 .env 변경 없음.
- `python -m unittest -v test_explain test_real_policy_mock`: 26개 통과.
- 가짜 시계와 sleep 모의 처리. 실제 Gemini 호출 없음.
- 일일 한도는 pacing으로 해결되지 않음. 여러 프로세스 및 다른 API의 호출은 이 제한 범위 밖.
- 상세 운영 제약은 README_real_policy.md 참고. 아래의 503 전용 수정 기록은 이전 이력임.

## 프로젝트 상태

- 위치: `<project-root>`
- FastAPI + google-genai 기반 보험 약관 AI 서버.
- 모델: 사용자 확인 기준 `gemini-3.5-flash-lite`. 코드는 `GEMINI_MODEL` 환경변수 사용.
- 인증은 `GEMINI_API_KEY` 사용. 키 값은 이 기록에 포함하지 않음.
- `/health`, `/ai/classify`, `/ai/extract`의 정상 동작 유지가 우선.
- 모델, 공유 Gemini 클라이언트 설정, `.env`를 임의로 변경하지 않기.

## 구현된 기능

### POST /ai/classify

- 문서 종류, 상품명, 버전 정보를 JSON으로 반환.
- 사용자 실제 Gemini 테스트 성공 이력 있음.

### POST /ai/extract

- 입력 조항에서 점검 항목별 원문 인용 추출.
- Pydantic 검증, 요청 항목 및 조항 ID 검증, 원문 부분 문자열 검증, 중복 제거.
- JSON 모드와 시스템 지시 사용.
- `response_schema=ExtractResponse`는 실제 API의 HTTP 400 원인이 되어 제거했음. 다시 추가하지 않기.
- 사용자 실제 테스트: HTTP 200, 6개 점검 항목 추출 성공.
- 주의: 기존 오류 로그 문자열에 `response_schema=ExtractResponse`가 남아 있지만 실제 요청 설정에는 없음. 이전 최소 수정 요청에 따라 로그는 유지했음.

확인된 점검 항목 번호:

1. 보험금을 주지 않는 경우(면책 사유)
2. 기다려야 하는 기간(면책·감액기간)
3. 보험료가 오를 수 있음(갱신형)
4. 알릴 의무(고지·통지의무)
5. 약관이 정한 뜻(질병·수술 등의 정의)
6. 중간에 해지하면(해지환급금)

### POST /ai/explain

- 입력: analysis_id, items(item_id, check_item, section, article_title, quote, retry_hint).
- 출력: cards(item_id, title, explanation, review, review_reason).
- 항목을 순차 처리. 해설 생성과 독립 의미 검토는 별도 Gemini 호출.
- 의미 검토 fail이면 한 번만 재생성 후 다시 검토. 두 번째도 fail이면 fail과 이유 반환.
- retry_hint의 unmatched_numbers를 생성 요청에 전달. 백엔드 최종 숫자 검증을 대체하지 않음.
- JSON 모드 사용, API response_schema 강제 설정 없음. 반환 JSON은 Pydantic 검증.
- 사용자 실제 단일 항목 테스트 성공 이력 있음.
- 6개 항목 실제 테스트 중 meaning_review에서 503 UNAVAILABLE 발생하여 전체 HTTP 502 반환 이력 있음. 당시 elapsed_ms=338577은 요청 누적 시간.

## 가장 최근 수정: explain 전용 503 복구

- `app.py`의 `explain_json_call`만 수정하고 `test_explain.py`에 테스트 추가.
- SDK APIError의 구조화된 HTTP 코드가 503인 경우에만 2초 대기 후 해당 호출을 1회 재시도.
- 최초 1회 + 재시도 1회. 400/401/403 등 다른 오류는 재시도하지 않음.
- 검토에서 실패하면 검토만 재시도하며, 성공한 해설 생성 단계는 반복하지 않음.
- 단계(generation/meaning_review), 재시도 횟수, HTTP 코드, 성공/최종 실패 상태를 안전하게 기록.
- 원문, 해설, 키, 예외 원문은 새 로그에 포함하지 않음.
- 재시도 실패 시 기존 전체 HTTP 502 유지. 부분 성공이나 errors 배열은 추가하지 않음.
- 기존 요청·응답 구조, 의미 검토 재생성 흐름, classify/extract, 공유 클라이언트 설정 유지.
- 공유 요청 제한 시간은 기존 120초. 최근 수정에서 변경하지 않음.
- 설치된 SDK 로컬 코드 확인 당시 retry_options=None이면 해당 요청 경로의 SDK 재시도 없음.

호출 횟수(모델 목록 조회 별도):

| 항목 수 | 최초 검토 모두 pass | 의미 검토로 모두 한 번 재생성 | 모든 생성/검토 호출에 503 재시도까지 발생하는 상한 |
|---|---:|---:|---:|
| 1 | 2 | 4 | 8 |
| 3 | 6 | 12 | 24 |
| 6 | 12 | 24 | 48 |

오류로 중단되면 실제 호출 횟수는 더 적음.

## 테스트 도구 및 데이터

- `diagnose_gemini.py`: 모델 목록, 지원 여부, 선택적 짧은 생성 테스트.
- `compare_gemini_versions.py`: 모델/API 버전 비교 진단.
- `test_real_policy.py`: 독립 HTTP 테스트. `--run` 없이는 서버/Gemini 호출하지 않음.
- `test_data/synthetic_policy.json`: 6개 항목의 가상 한국어 약관. 실제 보험사 약관 아님.
- `test_results/`: 명시적 실행 시 검증 결과 JSON 저장 위치.
- `README_real_policy.md`: 실행, 결과 형식, 실제 약관 입력 안내.
- 실제 PDF는 제공되지 않았으며 실제 보험 약관 테스트는 미실시.
- 실제 PDF 구조 분석은 Spring 백엔드 담당. 사람이 확인한 조항을 JSON으로 입력해야 함.
- 자동 테스트의 각 API 입력은 독립적이며 extract 결과를 explain으로 자동 전달하지 않음.
- 숫자 검사는 아라비아 숫자/단위의 참고용 문자열 검사. 의미·단위 환산·한글 수사 등을 최종 판정하지 못함.
- 결과 파일에는 원문/인용/해설 본문/개인 ID/서버 오류 본문을 저장하지 않음. review_reason은 존재 여부만 저장.

## 검증 결과

- 최근 `app.py`, `test_explain.py` Python 문법 검사 통과.
- 최근 `python -m unittest -v test_explain`: 13개 통과.
- 정상 무재시도, 생성 503 후 복구, 검토 503 후 복구, 연속 503의 502 반환, 다른 HTTP 오류 무재시도, 의미 검토 재생성, 기존 API 회귀 포함.
- sleep은 모의 처리했고 최근 변경 검증에서 실제 Gemini 호출은 하지 않음.
- 자동 테스트 도구 구축 시 `test_real_policy_mock`: 5개 통과, 문법/JSON/한국어 데이터 검사 통과.
- 503 재시도 추가 후 실제 서버 재검증은 아직 미실시.

## 다음 확인 작업

```powershell
# 오프라인 검증
python -m unittest -v test_explain
python -m unittest -v test_real_policy_mock

# 서버 실행
uvicorn app:app --reload

# 별도 터미널: 사용자가 명시적으로 실행할 때만 실제 Gemini 호출
python test_real_policy.py --api explain --run
```

먼저 단일 항목과 이후 여러 항목으로 검증하고, 503 발생 시 retry_scheduled/retry_succeeded/final_failure 로그 확인.
실제 호출은 사용자 요청 없이 수행하지 않기.

## 알려진 별도 사항

- 도구 구축 당시 requirements.txt에 `python-dotenvx` 항목이 관찰됨. 일반적인 패키지명은 `python-dotenv`이나 기존 파일 변경 금지에 따라 수정하지 않았음.
- 의존성 재설치 문제를 다룰 때 현재 파일을 다시 확인할 것.
# 2026-11-05 B안 구현 재개 및 오프라인 검증

- 재개 시 작업 폴더에서 B안 관련 저장 코드가 없음을 확인하고 구현을 진행함.
- `extract_recheck.py`: 내부 기본값 OFF, 점수+구체적 의미 단서, 최대 3항목,
  항목당 12조항/16,000바이트, 같은 조 문맥 보존.
- `app.py`: 1차 검증 후 선택적 재확인, 기존 검증·중복 제거로 병합.
  추가 시도 최대 6회(논리 호출 최대 3회와 각 1회 재시도), 추가 단계 120초 예산.
  최종 오류 시 전체 502, 기존 성공 일부를 200으로 반환하지 않음.
- 기본 OFF: 기존 호출 횟수와 계약 유지. classify/explain/F-13 처리 변경 없음.
- 기존 104개 + 재확인 회귀 19개 = 123개 오프라인 테스트 통과. 문법 검사 통과.
- 실제 Gemini 호출, .env 열람·변경, 모델 설정 변경, GitHub 커밋·푸시 없음.
- 실제 정확도 개선 여부는 아직 검증하지 않음. 배포에 새 모듈 포함 필요.
