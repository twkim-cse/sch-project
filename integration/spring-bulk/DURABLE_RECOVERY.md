# 대용량 분석의 로컬 재시작 복구

실제 New Dental 전체 Gemini 실행은 계정의 잔여 RPM/TPM/RPD와 과금 상태를 확인한 뒤 진행한다. 기존 26개 조항 소량 계획의 성공 part는 전체 1,472개 조항 계획과 다른 plan_id이므로 전체 77개 완료 수로 합산하지 않는다.

## 저장 설정과 보안

- Spring `AI_CHECKPOINT_DIR` 기본값은 실행 디렉터리의 `.local-state`이다. 빈 값이면 메모리 모드이며 재시작 복구를 보장하지 않는다.
- Compose backend는 `/app/.local-state`를 `analysis-state` named volume에 저장한다. `docker compose down -v`는 복구 자료를 삭제하므로 사용하지 않는다.
- 동일 디렉터리는 Spring 프로세스 하나만 사용한다. OS 파일 잠금으로 두 번째 writer를 차단한다. 다중 서버 운영에는 공유 DB 및 분산 잠금이 필요하다.
- 파싱된 원문, 계획, 인용 및 검토 결과가 로컬 JSON에 저장된다. API 키와 PDF 바이너리는 저장하지 않는다. 원문은 **평문**이며 암호화 저장소가 아니다. 운영 계정만 접근 가능한 디렉터리/볼륨을 사용한다.
- 임시 파일 작성, fsync, atomic rename, 체크섬 검증을 사용한다. 체크섬은 손상 탐지용이며 공격자에 대한 인증 수단은 아니다. 손상/권한 오류는 성공으로 처리하지 않는다.
- `.local-state`, 실제 PDF/JSON/로그는 Git 및 빌드 컨텍스트에서 제외한다. 기존 문서 삭제/보관기한 정리 시 연결된 분석·추출·해설 캐시도 삭제한다.

## 복구 절차

1. 같은 체크포인트 디렉터리와 같은 AI 모델로 Spring을 실행한다. 진행 중이던 분석은 `FAILED / ANALYSIS_INTERRUPTED`가 된다. READY 파싱 결과와 완료 분석은 복원된다. PDF 업로드 중 중단된 문서는 재업로드가 필요하다.
2. `GET /api/analyses/{analysisId}/progress`에서 완료/전체 part, 실패 범위, cooldown, `resumable`을 확인한다. 이 API는 part 진척도이며 Gemini가 모든 위험을 탐지했다는 정확도 보증이 아니다.
3. `resumable=true`이고 호출 예산을 다시 확인한 경우만 `POST /api/analyses/{analysisId}/resume`을 호출한다. 전체 PDF를 다시 업로드하거나 새 analysis_id로 중복 분석하지 않는다.
4. 성공 part는 재전송하지 않고 실패/미실행 part만 처리한다. HTTP part 시도 횟수(최대 2)는 재시작해도 초기화하지 않는다. 기존 AI 내부 생성 시도 최대 3회와 별도 계층이다. 413/422/인용 검증 오류는 같은 입력으로 재시도하지 않는다.
5. 성공한 explain HTTP 응답도 요청 내용·retry_hint·모델별로 저장한다. `review=fail`은 그대로 보존한다. 알려진 일시 오류는 cooldown/Retry-After 후 명시적 재개만 허용하며 HTTP 시도 최대 2회이다.
6. 전송 intent만 저장된 `in_flight`, 네트워크 결과 불명, 검증 실패는 자동 재개하지 않는다. 서버 재시작만으로 이전 호출이 실제 실패했다고 단정할 수 없다. upstream 로그/사용량을 확인한 후 운영자가 처리해야 한다.
7. 모든 필수 part 성공 후에만 merge하고, 해설/독립 검토 및 숫자 검증이 끝나야 DONE이다. 실패/미처리 범위는 위험 없음 또는 NOT_FOUND가 아니다.

## 호출 예산

전체 extract 정상 생성은 77회이다. 실제 추출 수를 E라 할 때 explain 생성+독립 검토의 정상 최소는 2E회이며 합계는 `77 + 2E`이다. E=50/100/200이면 각각177/277/477회이다. 의미 검토 재생성, JSON 형식 복구, 429/503/504 복구, Spring 숫자 retry_hint, 명시적 재개는 추가 호출이다.

extract의 한 HTTP pass에서 77 × 3 = 231회까지 upstream 생성 시도가 발생할 수 있고, 각 part의 수동 재개 1회를 모두 사용하면 최대462회이다. 이는 사용 권장이 아니라 최악 시도 상한이다. explain은 별도이다. 12초 시작 간격만 고려해도 77개 시작에 최소912초가 필요하며 실제 생성 시간과 cooldown이 추가된다. UTF-8 바이트를 토큰이나 비용으로 환산하지 않는다.

실행 전 **현재 키의 실제 프로젝트**에서 모델별 RPM/TPM/RPD, 사용/잔여량, 유료 결제 연결 여부 및 허용 비용을 확인한다. 확인 불가 시 전체 실행하지 않는다. 로컬 limiter는 다른 팀원/프로세스의 같은 프로젝트 사용량을 알 수 없다. 계정 전체 quota 및 비용 상한을 보장하지 않는다.

## 검증 범위와 배포

오프라인 WebClient/HTTP fixture로 저장·재시작·재개·최종 조회를 확인하며 실제 Gemini 성공과 구분한다. 팀원 환경은 미배포 상태이다. 작업 브랜치 diff 검토 → Spring/AI 회귀 테스트 → 별도 PR → 팀원 승인 후 merge → AI와 Spring 이미지 재빌드 및 backend 영속 볼륨 연결 순서로 적용한다. 이 작업에서는 push/PR/merge/배포하지 않는다.

프론트의 최종 실제 결과 화면 및 전체 Gemini 분석은 별도 확인 대상이다. 현재 프론트에 수동 재개 UI는 추가하지 않았으며 운영자 API로만 재개한다. Java21 런타임 및 Docker 실실행은 해당 환경에서 추가 검증한다.
