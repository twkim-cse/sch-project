# Spring 제한적 병렬 연동 제안 — 실제 backend/frontend 미수정

현재 `BulkExtractCoordinator.java` 제안은 순차 호출이다. 새 Python run_bounded_bulk는
참조 실행기이며 Spring에 자동 연결되지 않는다. Java 21에서 팀원 검토 후 다음을 구현해야 한다.

1. ExecutorCompletionService 또는 동등한 bounded pool. 기본 1, 실험 2, 상한4.
   전체 part 수만큼 future를 미리 제출하지 말고 각 job에서 최대 concurrency개만 유지한다.
2. 문서 간 공유 semaphore/시작 간격/일일 예산. 다중 JVM/AI worker면 중앙 저장소가 필요하다.
3. 성공 완료를 checkpoint에 원자적으로 저장하고 실패 후 새 제출 중단. 이미 실행한 요청은
   drain하여 성공을 저장. 영구 실패 413/422/원문 불일치를 재시도하지 않는다.
4. plan/selection ID, part ID, 누적 attempts/처리 시간, failed/pending/in_flight 범위를 저장.
   프로세스 crash 뒤 in_flight는 결과 불명확이므로 자동 재호출 금지.
5. 후보 분석이면 기존 후보 전용 merge를 사용한다. 전체/후보 모두 모든 필수 part 완료 전
   DONE/전체 성공을 반환하지 않는다. PARTIAL 상태 표시는 프론트 계약과 협의한다.
6. 개별 HTTP 대기 최대270초, 전체 처리 budget4시간 정책과 취소 시 drain을 함께 구현한다.
   현재 AiClient의 extract 전체 5분 block/무조건 1회 재시도는 여전히 부적합하다.
   기존 async AnalysisRunner/2.5초 저장소 폴링을 사용하고 프록시/executor/보관 시간을 점검한다.
7. explain은 이번 병렬화 대상이 아니다. 기존 생성/독립검토/재생성·240초 정책을 유지한다.

서버 설정은 `AI_EXTRACT_CONCURRENCY=1` 기본이다. 테스트 프로세스에서만 2로 올린다.
Compose는 호스트 환경변수를 자동 전달하지 않으므로 아래 예시 override가 필요하다.
이 예시는 기본1이고 실행하지 않았다. 기존 키/모델/B안은 수정하지 않는다.

```yaml
services:
  ai:
    environment:
      AI_EXTRACT_CONCURRENCY: ${AI_EXTRACT_CONCURRENCY:-1}
```

Dockerfile에 bulk_parallel.py/bulk_http.py/extract_gate.py 복사를 추가했다.
Docker 빌드, Java 병렬 helper, Spring HTTP 통합, 실제 Gemini 동시성은 미검증이다.

다음 실제 실험은 별도 승인 후 단일 문서 소량 2배치로 한정한다. client/server concurrency=2,
기존 생성/재시도 예산 유지, 429/503 최종 실패 시 전체 반복 금지. 먼저 계정별 RPM/TPM/RPD를
확인하고 요청·SDK 시도·재시도·최대 in-flight를 안전하게 계측한다. 성공하더라도 3~4 확대를
자동 결정하지 않는다. 이번 Mock 시간 감소율은 실제 서비스 속도 보장이 아니다.
