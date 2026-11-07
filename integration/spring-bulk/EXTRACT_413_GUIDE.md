# 후보 분석의 413 진단 및 Spring 호출 계약

## 이번 오프라인 확인

2026-11-07 확인한 원격 main은 `49126ec6cb3d146b96d20ab5e0b4c354d0dc0e32`이며
PR #17은 병합돼 있다. main의 app.py, candidate_filter.py, extract_planning.py,
bulk_extract.py는 PR head `2085ae3`과 같았다. 실제 팀원 서버의 배포 이미지/실행 경로는
접근하지 못했으므로 별도 확인해야 한다. 이번 로컬 변경은 아직 게시하지 않았다.

## 서로 다른 두 가지 예산

기존 `POST /ai/extract` 한 HTTP 요청의 **내부 Gemini 배치** 한도:

| 설정 | 값 |
|---|---:|
| MAX_BATCH_CLAUSES | 25 |
| MAX_INPUT_BYTES | 24,000 bytes |
| WIRE_RESERVE_BYTES | 2,048 bytes |
| MAX_BATCHES | 40 |

24,000은 text 길이나 HTTP Content-Length가 아니다. 직렬화된 check_items 정의와
clauses 전체 필드, system instruction UTF-8 길이, 2,048 reserve를 합산한다.
조항 하나라도 이 값을 초과하면 `single_clause_exceeds_input_budget`이다.
모든 단일 조항이 들어가더라도 계획 내부 배치가 40개를 넘으면
`request_exceeds_batch_limit`이다. 양쪽 모두 기존 HTTP 413 /
`{"code":"EXTRACT_INPUT_BUDGET_EXCEEDED","message":"..."}` 계약을 유지한다.
이번 변경은 로그의 고정 reason 및 숫자만 구분하며 원문/인용/ID는 기록하지 않는다.

후보/무손실 **plan API**는 전체 원본을 받아 별도 HTTP 요청들로 계획한다.
원본 한도는 10,000조항/직렬화 16MB, 생성 part 상한은 500이다. part 예산은
내부 한도를 만족하도록 계산하며 각 part.request는 기존 extract의 **1 내부 배치**가 된다.
따라서 plan의 84 parts는 한 HTTP 요청 안의 84 내부 배치라는 뜻이 아니다.
과대 단일 조항은 완전한 문장/문단/목록 경계에서 무손실 분리하고 원 ID와 위치를 추적한다.
안전한 경계가 없거나 전체 예산을 넘으면 오류이며, 임의 절단/안전 제한 해제는 하지 않는다.

## 정확한 호출 순서 (snake_case)

기존 ExtractRequest `source`:

```json
{"analysis_id":"document-revision","check_items":[1,2,3,4,5,6],"clauses":[
  {"clause_id":"original-id","rider_id":null,"section":"주계약","article":"제1조",
   "article_title":null,"paragraph":null,"page":1,"from_table":false,"text":"입력 원문"}
]}
```

1. `POST /ai/extract/candidates/plan`

```json
{"source_request":{"analysis_id":"document-revision","check_items":[1,2,3,4,5,6],"clauses":[]},
 "mode":"recall","focus_items":null}
```

위 clauses 빈 배열은 형식 설명용이며 실제로는 source 원본 조항 전체를 넣는다.
응답은 `{selection, candidate_plan}`이다. selection에는 selection_id, policy_version,
decisions(원 ID·선정 항목·이유), coverage가 있으며 candidate_plan에는 plan_id,
parts, fragments(원 ID/문자 위치/문맥 매핑)가 있다. 원본 revision, mode/focus,
두 ID와 계획을 체크포인트에 보관한다. 원문이 포함된 계획/응답은 로그에 출력하지 않는다.

2. **각 `candidate_plan.parts[].request` 객체 그대로** `POST /ai/extract`에 보낸다.
   응답은 기존 `{extractions:[{check_item,clause_id,quote}]}`이다.
   part_id와 응답을 함께 저장한다. 후보 전체(candidate_request), 전체 source,
   part들을 합친 clauses를 한 번에 extract로 보내면 안 된다.
   처음에는 concurrency=1. 병렬 실행 시 bounded runner 및 계정 보호 제한을 유지한다.

3. 모든 필수 part 성공 후에만 `POST /ai/extract/candidates/merge`:

```json
{"source_request":{"analysis_id":"document-revision","check_items":[1,2,3,4,5,6],"clauses":[]},
 "mode":"recall","focus_items":null,
 "selection_id":"selection.selection_id","plan_id":"candidate_plan.plan_id",
 "part_results":[{"part_id":"해당 part_id","response":{"extractions":[]}}]}
```

응답은 `{extractions,coverage,selection_id,plan_id}`이다. 기존 ExtractResponse만으로
역직렬화하고 coverage를 버리지 않는다. 후보 경로에 `/ai/extract/merge`를 섞지 않는다.
여기에도 계획 시 사용한 원본 조항 전체를 동일하게 넣어야 한다.
기존 무손실 전체 분석은 `/ai/extract/plan` → parts[].request를 extract →
`/ai/extract/merge` 경로이며 원래 계약을 유지한다.

4. 검증된 최종 extraction만 explain 입력으로 변환한다. review=fail은 QUOTE_ONLY를 유지한다.

`/ai/extract/candidates`는 선정만 수행해 candidate_request를 반환한다. 분할 실행을
대신하지 않는다. API 경로가 없으면 404, wrapper/필드 불일치면 422이며 413과 구분한다.

## 실패·진행 상태

| 상태 | Spring 처리 |
|---|---|
| 413 | 동일 요청 재시도 금지. reason/크기 확인 후 올바른 plan 경로 사용 또는 조문 분리 오류 조사 |
| 422 / plan mismatch | 원본 revision·mode·focus·ID·응답 스키마 확인, 자동 재시도 금지 |
| 409 BULK_INCOMPLETE | 누락/실패 part 보완 전 merge 및 완료 표시 금지 |
| 429/503, 502 upstream | AI 내부 제한 재시도 후 최종 실패면 새 작업 시작 중단, 성공 체크포인트 보존 |
| 연결/시간 초과 | 서버 처리 여부 불명확. 무조건 전체 HTTP 중복 실행 금지, 로그/체크포인트 확인 |

`GEMINI_API_ERROR` 하나만으로 원래 upstream 상태가 400인지 503인지 알 수 없다.
AI 로그의 stage/http_code와 Spring의 part_id/시도 횟수를 함께 확인한다.
extract의 기존 내부 재시도는 429/503에 제한되며 explain의 504 정책과 혼동하지 않는다.

PARTIAL은 전체 성공이 아니다. 완료/실패/대기 part ID와 원 clause ID 범위를 보관한다.
progress API body는 위 source/mode/focus/IDs에 completed_part_ids, failed_part_ids를 추가한다.
응답의 failed_ranges/pending_ranges와 advisory 상태를 사용하되 실제 완료 검증은 merge가 한다.
Spring 저장소의 진행률을 프론트에서 폴링하고 매번 전체 원문을 AI에 다시 보내지 않는다.
후보 미선정·실패·대기 조항은 **미분석/확인 필요**이며 NOT_FOUND/위험 없음이 아니다.
후보 완료도 전체 문서의 위험 부재를 보증하지 않는다(`risk_absence_determined=false`).
새 규칙 버전에서는 selection_id/plan_id가 바뀌므로 예전 체크포인트와 섞지 않는다.

## backend 담당자에게 받을 로그 (원문·키·응답 본문 제외)

- AI 이미지 digest/커밋, 실행 경로, OpenAPI의 candidate plan/merge 경로 존재 여부.
- 실제 호출 URL/path, source 조항 수·직렬화 byte 수·revision, check_items.
- plan selection_id/policy_version/plan_id, 후보 수·part 수·part별 조항 수와 input byte 수.
- 실행한 part_id/시도 횟수/시간/HTTP/code, AI stage/reason 및 수치 예산 진단.
- 실패 후 전체 clauses를 다시 보내거나 점검 번호만 나눠 같은 clauses를 재전송하는지 여부.
- 필수 part 누락 여부, merge HTTP/code, Spring 역직렬화 실패인지 upstream 실패인지.

Spring 실제 변경 협의 위치는 AiClient.extract/후보 plan·merge DTO,
AnalysisRunner의 분할 실행·전체 재시도·NOT_FOUND fallback,
AnalysisRecord/Store의 체크포인트·장시간 작업 예산이다. 기존 5분 동기 제한을 유지한 채
66~84 요청 전체를 기다리기는 어렵다. 백그라운드 실행·폴링·명시적 재개가 필요하다.
AI 서버는 처리 상태를 영구 저장하지 않는다. 실제 Spring 소스는 이번에 수정하지 않았다.

## 재현 검증 결과

| 문서 | 원본 | 후보 전→후 | HTTP parts 전→후 | 기존 extract에 후보 전체 전송 |
|---|---:|---:|---:|---|
| ABL | 1,981 | 1,760→1,760 | 84→84 | 98 내부 배치, 상한 초과 |
| CM295F | 1,750 | 1,426→1,420 | 83→83 | 단일 24,254 bytes부터 초과 |
| Dental | 1,472 | 1,186→1,186 | 66→66 | 단일 37,356 bytes부터 초과 |

항목별 후보 태그 수(한 조항이 여러 번호에 포함될 수 있음):

| 문서 | 1 | 2 | 3 | 4 | 5 전→후 | 6 |
|---|---:|---:|---:|---:|---:|---:|
| ABL | 1,243 | 1,385 | 685 | 991 | 1,033→1,033 | 1,474 |
| CM295F | 1,056 | 1,064 | 461 | 584 | 1,080→1,074 | 718 |
| Dental | 884 | 975 | 533 | 472 | 950→950 | 589 |

본문 신호 없이 문맥 확장만으로 포함된 조항은 ABL 510, CM 289, Dental 241개다.
제목 신호가 있지만 본문 직접/복합 근거가 없는 후보는 각각 20/80/98개다(범주 간 중첩 가능).
잘못 연결된 거대 article 메타데이터와 넓은 지급+기간/임상+기준 신호가 후보 증가 원인이
될 수 있지만, 이 숫자만으로 불필요한 조항이라고 판정할 수는 없다. 표·예외·주변 문맥과
일반 계약 규정 안의 실제 보장 제한을 놓칠 수 있어 이 범주를 일괄 제거하지 않았다.

세 문서의 모든 plan part를 실제 FastAPI 경로에 Mock Gemini로 보내고 전용 merge 200,
누락 part merge 409, 잘못된 전체용 merge 422를 확인했다. 생성 응답은 가상 빈 결과이며
실제 위험 탐지 성공을 의미하지 않는다. 원본 SHA와 후보 필드/분리 원문 보존을 확인했다.
233회 Mock 생성, 실제 Gemini 호출 0회. 소스 테스트 289개 통과.
계정 5 starts/min·12초 간격이면 233개 호출 시작에만 약 46.4분이 필요할 수 있어
필터만으로 5분 SLA나 무료 일일 한도를 보장하지 않는다.

이번 조정은 CM의 잘못된 의료 제목에만 기대던 형사 법규 6개를 추가 제외한다.
목차는 모든 줄이 조문 제목+페이지로 완결된 다중 행일 때만 추가 제외한다.
실제 금액·표·단일 제목·보험/의료/적립금 반환 본문 신호·애매한 문맥은 유지한다.
제외한 6개는 CM PDF 미확보로 원 PDF 대조가 안 됐으며, 법규가 다른 보험 조항에서
참조될 가능성은 남는다. 본문 직접 위험 신호 및 full fallback으로 방어하되 배포 전
참조 법규/미선정 조항을 사람과 PDF로 확인해야 한다. ABL 양성 11/11 유지·태그 일치이며
세 문서 전체 재현율은 검증하지 못했다. 한도를 맞추기 위해 후보를 강제로 줄이지 않았다.
