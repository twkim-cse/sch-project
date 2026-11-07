# extract 503 복구 — 범위와 검증 결과

이 변경은 `fix/spring-lossless-extract`의 로컬 변경이다. 모델·.env·API 키·B안 OFF와
explain의 생성/독립 검토/재생성/503·504 정책을 변경하지 않는다. 아직 미배포다.

## 정책

- 논리적인 Gemini 배치 생성당 최대 **3회**(최초 1 + 재시도 2). Spring plan의 각 part는
  내부 1배치라 해당 part의 생성도 최대 3회다. 기존 큰 extract 요청의 여러 배치를 합쳐
  생성이 3회뿐이라는 뜻은 아니다. 부분 결과를 HTTP 200으로 반환하지 않는다.
- 503 재시도 간격은 2~3초, 4~5초: 지수 백오프 + 0~1초 jitter.
- 구조화된 HTTP 503만 이 정책으로 재시도한다. 400/401/403/413/422는 재시도하지 않는다.
  기존 429는 최대 한 번만 재시도하며 기존 대기 정보를 사용한다. extract의 504 재시도를
  새로 추가하지 않았다. explain의 별도 504 정책과 구분한다.
- SDK `HttpRetryOptions(attempts=1)`을 extract의 호출별로 강제한다. 공유 클라이언트,
  classify, explain 전역 설정은 변경하지 않는다. 설치 SDK 2.33.0 + MockTransport로
  앱 3시도일 때 실제 HTTP transport도 정확히 3회임을 검증했다.
- 단일 SDK 시도 90초 이하, 논리 배치 총 300초(호출 제한 대기 포함), 모델 조회 15초 설정.
  SDK timeout은 네트워크 협조형이며 OS 수준의 강제 실행 취소를 보장하지는 않는다.
- Retry-After(초/HTTP 날짜) 또는 google.rpc.RetryInfo가 있으면 그보다 일찍 재시도하지 않는다.
  503 대기 합계 15초 또는 남은 호출 시간에 들어가지 않으면 오래 잠들거나 조기 재시도하지
  않고 checkpoint/cooldown 경로로 빠진다.
- 마지막 503 뒤 기본 60초 cooldown. 명시적 대기 정보가 더 길면 더 길게 유지한다.
  cooldown 중 extract의 모델 목록 조회/생성을 모두 생략하고 기존 502 code/message와
  안전한 숫자 Retry-After 헤더를 반환한다. plan/merge의 순수 계산과 413 검증은 유지한다.
- Spring은 Retry-After를 체크포인트에 반영하고 progress.retryAfterSeconds로 노출한다.
  해당 시간이 지나기 전에는 명시적 재개도 part를 재전송하지 않는다. 성공한 part는 보존한다.
  Python HttpPartSender/Admission도 502로 래핑된 upstream cooldown 헤더를 존중한다.
- cooldown은 AI 프로세스 단위다. Spring은 Retry-After와 성공 part를 영속 체크포인트에
  저장한다. 단일 writer의 재시작 복구이며 여러 인스턴스의 계정 전체 제한은 보장하지 않는다.
  [복구·보안·재개 절차](../integration/spring-bulk/DURABLE_RECOVERY.md)를 따른다.

## 실제 검증 (2026-11-07)

기존 PDF 업로드·파싱과 전체 77개 분할 계획은 반복하지 않고 보존된 자료를 사용했다.

| 실제 테스트 | 결과 | 생성 호출 | 시간 |
|---|---|---:|---:|
| 같은 키·gemini-3.6-flash 최소 입력 직접 호출 | HTTP 200, 텍스트 존재 | 1 | 3.188초 |
| 이전 실패한 동일 21개 조항 part | HTTP 200, 2개 인용 검증 | 1 | 11.390초 |
| 그중 1개 해설+독립 검토 | HTTP 200, review=pass | 2 | 18.688초 |
| 재파싱 없이 실제 파싱 조항 1개의 Spring 분석→저장→조회 | 202→DONE→200, FULL 카드 1개 | 5 | 52.971초 |

합계 실제 생성 9회(목록 조회 제외), 이번 실제 실행의 HTTP 재시도 0회,
503/504/429 0회다. Spring 실행의 5회는 extract 1 + 초안 2 + 검토 2이며,
explain POST 로그는 1회다. 기존 의미 검토 fail 후 1회 재생성이 작동하고 최종 pass가 됐다.
전체 PDF 분석이나 React 결과 화면까지 성공했다는 뜻은 아니다.

이전 같은 part의 두 시도는 모두 503이었다. 이번에는 추가 재시도를 쓰지 않고 성공했으므로
**백오프 변경 덕분에 외부 장애가 해결됐다고 판단할 수 없다.** 최소 입력과 실제 입력 모두
현재 응답 가능하다는 사실만 확인했다. 지속 503/cooldown 복구는 오프라인 회귀로 검증했다.

작은 26조항 계획의 성공한 첫 part는 해당 plan_id와 함께 로컬 체크포인트에 저장했다.
이는 전체 1,472조항/77 parts 계획과 **다른 계획**이다. 전체 계획의 완료 part로 표시하거나
해시 검증 없이 옮기면 안 된다. 전체 분석을 승인받은 뒤에는 동일 전체 계획의 성공 checkpoint만
재사용해야 한다. Spring 실제 검증도 한 조항 scope이며 77개 전체 저장 검증이 아니다.

## 남은 운영 조건

- 실제 RPM/TPM/RPD, 오늘 남은 사용량과 토큰 비용은 API 키 설정이나 최소 HTTP 200만으로
  알 수 없다. 계정 보호용 테스트 간격 12초는 실제 프로젝트 한도의 증거가 아니다.
- [Google rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)에 따르면 한도는
  키가 아닌 프로젝트 단위이고 모델별로 다르다. AI Studio에서 현재 프로젝트의 실제 한도와
  다른 팀원 사용량까지 확인한 뒤 전체 77회 + 해설/검토 호출을 계획해야 한다.
- [공식 오류 안내](https://ai.google.dev/gemini-api/docs/generate-content/api-errors)는 503을
  일시적 과부하/서비스 불가로 설명한다. 이번 프로젝트의 정확한 서버 측 원인은 확정하지 못했다.
- 이번 작업에서 자동 모델 전환을 하지 않는다. 대체 모델의 동일 프로젝트 실제 호출 가능
  여부와 한도는 별도 승인된 소량 비교가 필요하다.
- React 전체 E2E는 전체 분석 예산 및 브라우저 접근이 확인된 뒤 진행한다. 기존 브라우저
  URL 확인 제한과 팀원 배포 환경은 미검증이며 이 변경을 배포했다고 주장하지 않는다.

## 안전한 로그

extract 로그는 시도 번호/재시도 수/timeout/elapsed/http_code/고정 reason과
cooldown_seconds만 기록한다. 원문, 인용, Gemini 응답, 키, 예외 원문은 기록하지 않는다.
최종 502는 성공이나 NOT_FOUND가 아니다. PARTIAL/FAILED와 미처리 범위를 유지한다.

## 회귀 검증

Python 오프라인 304개 통과(기존 289 + 복구 회귀 15). 최신 Spring 및 재시작 복구 테스트
결과는 PR의 최종 검증 표를 따른다. 선택적 실제 Gemini/PDF 테스트는 기본 실행하지 않는다.
`test bootJar`도 성공했다. 별도 opt-in 실제 Spring 소량
테스트 1개는 성공했고, 그 증거/결과는 저장소 밖 로컬 테스트 폴더에 보존했다.
로컬 JDK 23 + Java 21 API/bytecode 옵션에서 실행했으며 Java 21 런타임/Docker는 미검증이다.
프론트 소스는 이번에 변경하지 않았다. 실제 Gemini 테스트 프로세스는 종료했다.
