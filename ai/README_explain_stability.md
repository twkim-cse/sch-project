# explain 실행 안정화 — 2026-11-06

이 변경은 Gemini 장애를 없애거나 모델을 교체하지 않습니다. 기존 생성 → 별도 의미 검토 →
검토 fail 시 최대 한 번 재생성 흐름, JSON 형식 복구, F-13, 요청/응답 계약을 유지합니다.

## 시간·호출 예산

| 범위 | 설정 |
|---|---|
| 생성 호출 | 60초 또는 남은 요청 시간 중 작은 값 |
| 의미 검토 호출 | 45초 또는 남은 요청 시간 중 작은 값 |
| 모델 목록 조회 | 각 SDK 요청에 15초 제한 |
| explain HTTP 처리 예산 | 240초, 조회·호출 제한 대기·백오프·검증 포함 |
| 생성·검토 실제 시도 | 요청 전체 최대 32회, 형식 복구·재생성·재시도 포함 |
| API 오류 추가 재시도 | 요청 전체 최대 2회, 생성과 검토가 공유 |

각 호출의 SDK `HttpRetryOptions(attempts=1)`을 명시해 SDK와 애플리케이션 재시도의 중첩을
방지합니다. 공유 Gemini 클라이언트의 전역 설정과 classify/extract 호출 설정은 유지합니다.
HTTPX timeout은 네트워크 작업 제한이며 강제 프로세스 취소 타이머가 아닙니다. 240초는
핸들러 시작 이후 각 단계·대기·호출 전후에 확인하는 예산입니다. 서버 작업 큐에서 핸들러
진입을 기다리는 시간과 프록시 대기는 별도입니다. SDK/OS 지연으로 정확히 240초에 작업이
강제 중단된다고 보장하지 않습니다. 늦게 돌아온 응답도 예산 초과 시 성공으로 반환하지 않습니다.

## 오류 정책

- 구조화된 APIError의 HTTP 503/504는 해당 호출만 재시도합니다. 요청 전체에서 첫 추가
  재시도는 2초, 두 번째는 4초를 기다립니다. 한 호출이 이 예산을 모두 쓰면 다른 단계에는
  추가 재시도가 남지 않습니다. 호출당 최대 최초+2회입니다.
- 429는 기존 Retry-After/RetryInfo 우선 정책과 호출당 최초+1회 한도를 유지하되 전체
  추가 재시도 예산을 공유합니다. 대기가 남은 시간 이상이면 기다리지 않고 시간 오류로 종료합니다.
  일일 할당량 초과는 호출 간격 조절로 해결되지 않습니다.
- HTTP 400/401/403/404, 클라이언트 ReadTimeout·연결 오류는 무조건 재시도하지 않습니다.
  클라이언트 타임아웃 후 서버 측 실행 여부가 불명확하므로 중복 생성을 피합니다.
- 생성 성공 후 검토 API가 실패하면 검토만 재시도합니다. 최종 검토 호출 실패는 전체 502이며
  검증되지 않은 해설이나 앞 항목만 성공한 카드를 200으로 반환하지 않습니다.
- 정상 검토 결과가 fail이면 기존 한 번 재생성·재검토를 유지합니다. 두 번째도 fail이면
  기존 카드 구조와 fail을 유지하며 Spring에서 QUOTE_ONLY로 처리합니다.
- 시간 예산 초과/최종 504는 기존 GEMINI_TIMEOUT, 호출 수 예산 초과는 기존 GEMINI_API_ERROR의
  code/message 502 응답을 사용합니다. 요청 필드나 응답 필드를 추가하지 않았습니다.

요청 범위 상태는 ContextVar로 분리하고 finally에서 복원합니다. 전역 모델·키·B안 설정은
변경하지 않습니다. 현재 호출 속도 제한도 공유하며 모든 실패 시도가 호출 수에 포함됩니다.

## 안전한 진단

단계(generation/meaning_review), 항목 순번, 요청 내 시도 번호, 재시도 번호, HTTP 코드,
호출별 소요 시간, 재시도 대기, 예산 소진 원인을 기록합니다. 호출 소요 시간은 백오프와
구분합니다. 서버 504와 클라이언트 타임아웃, 요청 예산 초과도 구분합니다.
키·원문·생성 해설·원본 예외 문자열·사용자 item_id는 추가 로그에 기록하지 않습니다.

기존 3.8 Flash 테스트의 118.656초 실패는 `ServerError`, HTTP 504,
`DEADLINE_EXCEEDED`였으므로 클라이언트 ReadTimeout과 구분해야 합니다. 이전 120초 설정과
시간이 비슷하다는 사실만으로 Google/중간 서버 내부의 원인을 확정할 수 없습니다.

## Spring 팀 협의 사항 — 이번에는 수정하지 않음

`backend/.../analysis/AiClient.java`는 5분 제한이며 모든 RuntimeException에 대해 전체
extract/explain 요청을 한 번 더 실행합니다. 현재 AI 예산은 이 값보다 짧게 설계했습니다.
다만 SDK/OS 취소 지연과 프록시 제한도 통합 환경에서 확인해야 합니다.
요청 검증·설정 오류 및 예산 초과의 무조건 재시도를 중단하고, 일시 오류 재시도를 AI가
담당하도록 책임을 분리하는 방안을 권장합니다. 긴 항목 목록은 Spring에서 2~3개씩 순차
호출하는 방안을 협의하되, 전체 검증 완료 전 DONE으로 표시하지 않아야 합니다.
32회 예산 또는 240초를 초과한 큰 요청은 이제 명시적으로 실패할 수 있습니다.

## 오프라인 검증

기존 테스트의 503/504 최종 실패 기대는 새 요청 전체 재시도 정책에 맞게 갱신했습니다.
테스트를 삭제하지 않았습니다. 신규 23개는 시간 예산·백오프·단계 분리·오류·로그 안전성,
실제 SDK+HTTPX MockTransport의 timeout 전달과 숨은 재시도 부재를 검증합니다.
모든 네트워크와 실제 대기는 Mock 처리하며 실제 Gemini API를 호출하지 않습니다.
기존 173개와 신규 23개, 총 196개 오프라인 테스트가 게시용 디렉터리에서 통과했습니다.
현재 로컬 Python 3.10 환경의 결과이며 Docker/Python 3.12와 실제 Spring 연동은 별도 검증입니다.

```powershell
python -m unittest -q test_explain test_real_policy_mock test_contract_audit test_extract_validation test_extract_batching test_extract_categories test_extract_refund test_extract_recheck test_extract_duty test_extract_recheck_instruction test_extract_accuracy test_refund_postvalidation test_extract_pipeline test_explain_stability
```
