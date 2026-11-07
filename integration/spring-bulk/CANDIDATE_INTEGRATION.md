# 후보 필터링 Spring 연동 제안 — 기존 팀원 파일 미수정

## 호출 계약

1. 원본 ExtractRequest를 AnalysisRecord/보관소에 그대로 유지한다. mode와 focus를 작업 설정으로 고정한다.
2. `/ai/extract/candidates/plan`에 `{source_request,mode:"recall",focus_items:null}`를 보낸다.
3. selection_id + candidate_plan.plan_id + 원본 revision으로 체크포인트를 식별한다.
4. candidate_plan.parts[].request를 기존 `/ai/extract`로 순차 호출한다.
5. 실패 시 즉시 중단한다. 성공한 part.response만 보관한다. 기존 retry/시간 상한을 공유한다.
6. 전부 완료한 경우 **`/ai/extract/candidates/merge`**에 전체 원본, 동일 mode/focus/IDs,
   part_results를 보낸다. 응답은 `{extractions,coverage,selection_id,plan_id}`이다.
7. coverage를 결과와 함께 보관하고, extract 인용을 원래 전체 조항으로 대조한다. explain은 검증된
   항목만 소량 순차 호출한다. review=fail은 기존 QUOTE_ONLY 정책을 유지한다.

AI는 후보 조항에 대한 원문·필드 보존을 검증한다. 미선정 조항까지 모델이 분석했다거나,
후보/추출이 0개인 항목의 위험이 실제로 없다고 판단하지 않는다.

## 기존 코드에서 검토할 위치

- `analysis/AiClient.java`: 별도 candidates plan/merge 메서드. 원래 세 API 계약은 유지.
- 제안 `BulkExtractCoordinator.java`: plan 응답의 selection/candidate_plan 래퍼 처리,
  키에 selection_id/mode/focus 추가, 후보 전용 merge 응답을 기존 ExtractResponse와 구분.
  기존 helper는 전체 bulk 계약용이므로 endpoint 문자열만 바꾸면 안 된다.
- `analysis/AnalysisRunner.java`: EXTRACTING 내부에서 후보 계획 실행, 실패 즉시 중단,
  추출 0개 항목의 broad/full 명시적 추가 작업과 호출 예산·원문 대조·중복 병합 정책.
- `AnalysisRecord.java`, `AnalysisStore.java`: 선택 범위, rule version, 보존 원본 digest,
  completed/failed/pending part IDs, 시도 수·시간·오류 코드, source ID 범위 저장.
  권한·F-21 삭제·내구성/동시성은 기존 bulk 패치와 동일하게 연동 필요.
- `analysis/dto/StatusResponse.java`, `AnalysisController.java`: 안전한 선택 범위·부분 처리 현황 노출.
- `AnalysisStatus.java`: 기존 enum에 PARTIAL을 추가하려면 프론트 파서/상태 전이와 합의 필요.
  합의 전에는 기존 FAILED/RUNNING과 별도 processing_detail.state="PARTIAL" 조합을 권장.
- `AnalysisRunner.finalize`와 `dto/ResultResponseBuilder.java`: 후보 미검출을 전체 문서의
  NOT_FOUND로 단정하지 않도록 검색 범위/미분석 수/확인 필요 표시를 반영해야 함.

이번에는 위 실제 Spring/프론트 파일을 수정하지 않았다. 후보용 Java 컴파일/HTTP 통합도 미실시다.

## PARTIAL과 재개

`/ai/extract/candidates/progress`는 동일 source/mode/focus와 selection_id/plan_id,
completed_part_ids/failed_part_ids를 받는다. PARTIAL 시 failed_ranges/pending_ranges에
원래 clause_id가 포함된다. 원문이나 중간 해설은 반환하지 않는다.

이 응답은 백엔드가 제출한 체크포인트 ID를 검증한 상태이며, 완료된 결과의 진위를 보증하지 않는다.
백엔드의 인증된 체크포인트 저장소만 이 값을 생성해야 한다. merge는 모든 결과 본문을 다시 검증한다.
후보 선정·계획은 한 번 계산해 저장하고 기존 2.5초 프론트 폴링은 Spring 저장소를 조회한다.
매 폴링마다 전체 원문을 AI progress 경로로 다시 보내거나 후보 계획을 재계산하지 않는다.
PARTIAL은 정상 완료/DONE이 아니며 아직 검토하지 않은 해설을 보여주면 안 된다.

같은 계획이면 실패한 부분부터 명시적으로 재개한다. 413/422·검증 오류는 재시도하지 않는다.
mode/focus나 원본을 바꾼 broad/full fallback은 새 계획이며 기존 결과를 무조건 재사용하지 않는다.
GEMINI_API_ERROR는 원래 400/503 등을 구분하지 못하므로 로그 확인 후 명시적 재개만 허용한다.

## 운영 반영 전

- AI 이미지에 candidate_filter.py가 들어가고 신규 경로가 등록되는지 확인.
- 기존 2번 최종 분류는 기간 제한 중심이다. 후보의 지급률/보장한도 태그를 최종 기능 범위로
  확대할지는 명세서·백엔드·해설 항목 이름과 함께 별도 결정해야 한다.
- 6번 후보의 단순 대출 안내는 검색 단계에서 유지하며 기존 refund 후처리에서 판단한다.
- 조항 원문/메타데이터 오류를 교정하지 않는다. PDF 표본 대조와 미선정 위험 조항 수작업 검토 필요.
- 후보 분석 완료와 전체 문서 검토 완료를 구분하는 프론트 표시·NOT_FOUND 의미를 합의한 뒤 배포.
