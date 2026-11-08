# AI 추출 API — 백엔드 담당자 전달 명세

이 문서는 AI 전용 브랜치의 계약이다. Spring AnalysisRunner/AiClient/저장소/재개 API는 변경하지 않는다. 게시 준비 시 main에 병합된 백엔드PR19의 DTO/호출코드는 읽기만 확인했다. 런타임 HTTP 통합 테스트는 별도로 필요하다.

## 책임과 기존 계약

AI는 후보 선정, 무손실 계획, 개별 생성·원문 인용 검증, 최종 병합을 제공한다. Spring은 HTTP part 실행·저장·재시도/재개·상태/최종결과·인증/삭제를 담당한다. plan/merge는 Gemini를 호출하지 않으며 AI 서버에 영속 작업을 만들지 않는다. AI 프로세스의 호출 gate/cooldown은 계정 전체 사용량이나 영속 체크포인트를 대신하지 않는다.

기존 `/health`, `/ai/classify`, `/ai/extract`, `/ai/explain`의 요청/응답 필드를 바꾸지 않았다. `ExtractRequest`는 다음 구조다. 아래 원문은 합성 예시다.

```json
{"analysis_id":"synthetic-001","check_items":[1,2,3,4,5,6],"clauses":[
  {"clause_id":"c1","rider_id":null,"section":"주계약","article":"제1조",
   "article_title":"보험금을 지급하지 않는 사유","paragraph":"제1항","page":1,
   "from_table":false,"text":"회사는 고의로 발생한 손해에 대해서는 보험금을 지급하지 않습니다."}
]}
```

`analysis_id`, 원문·metadata·순서, mode/focus를 작업 도중 바꾸지 않는다. nullable 필드와 snake_case를 유지한다. 여러 항목에 같은 조항이 추출될 수 있다.

## A. 팀원이 구현 중인 후보 분석 경로

1. `POST /ai/extract/candidates/plan`

```json
{"source_request":{"analysis_id":"synthetic-001","check_items":[1,2,3,4,5,6],"clauses":[]},
 "mode":"recall","focus_items":null}
```

`clauses:[]`는 형식 설명용이며 실제 요청은 원본 조항 전체를 담는다. 기본 mode=`recall`; 허용 mode=`recall|broad|full`; focus_items는 null 또는 요청 항목의 비어 있지 않은 부분집합이다.

응답 **래퍼**는 `{selection, candidate_plan}`이다. `selection`에는 selection_id, policy_version, mode, decisions(원 clause_id/source_index/선정여부/check_items/evidence/unselected_reason), coverage가 있다. `candidate_request`는 plan 응답에 없다. `candidate_plan`은 plan_id, parts, fragments 및 보존/경계검토 메타데이터를 포함한다. 기존 후보선정 전용 `/ai/extract/candidates`는 candidate_request를 반환하지만 **분할 실행의 대체가 아니다**.

2. `candidate_plan.parts[]`마다 `part_id`와 `request`를 보관한다. **`part.request` 객체 자체를 그대로** `POST /ai/extract`에 보낸다. 전체 후보를 다시 합치거나 전체 wrapper/part_id를 extract 요청에 추가하지 않는다. 응답은 기존 `{extractions:[{check_item,clause_id,quote}]}`다. 생성 호출은 이 단계에서만 발생한다. part별 성공 응답을 part_id로 저장한다.

3. 모든 필수 part가 성공하면 `POST /ai/extract/candidates/merge`:

```json
{"source_request":{"analysis_id":"synthetic-001","check_items":[1,2,3,4,5,6],"clauses":[]},
 "mode":"recall","focus_items":null,"selection_id":"계획응답의 selection_id",
 "plan_id":"candidate_plan.plan_id",
 "part_results":[{"part_id":"해당 part_id","response":{"extractions":[]}}]}
```

다시 **동일한 전체 원본**을 source_request에 넣는다. 원본/mode/focus/규칙버전으로 계획을 재검증한다. part_results는 정확히 한 결과씩이며 HTTP 실패를 빈 extractions로 대체하지 않는다. 성공한 빈 extractions는 유효한 결과다.

응답은 `{extractions,coverage,selection_id,plan_id}`다. coverage를 버리고 ExtractResponse만 역직렬화하지 않는다. 정상 후보완료 status는 `CANDIDATES_COMPLETE`, `all_candidates_completed=true`다. `full_document_success`는 보수적으로 false이며, `risk_absence_determined=false`/`recall_verified=false`를 위험 없음 판정으로 바꾸지 않는다. 모든 원문이 후보였을 때만 `full_document_analyzed=true`가 될 수 있으나 정확도 보증은 아니다.

4. 최종 검증된 추출만 기존 explain으로 변환한다. item_id를 백엔드가 안정적으로 부여하고 quote/section/article_title/check_item을 연결한다. AI 독립 검토 fail은 기존 QUOTE_ONLY 정책을 따른다. 이 문서가 Spring 저장·표시 동작을 구현하지는 않는다.

## B. 기존 전체 분석 경로도 유지

`POST /ai/extract/plan`은 **ExtractRequest 자체**를 받으며 `{selection,candidate_plan}` wrapper가 아닌 계획 객체 자체를 반환한다. `parts[].request`를 extract로 보내고, 모두 성공 후 `/ai/extract/merge`에 `{source_request,plan_id,part_results}`를 보낸다. 응답은 `{extractions}`다.

전체 plan을 candidates/merge에, 후보 plan을 전체 merge에 보내면 안 된다. mode=full인 후보 계획도 후보 전용 selection_id와 merge를 사용한다. fragment→원본 ID/문자 위치 복원은 AI merger에 맡기며 Java UTF-16 인덱스로 Python Unicode offset을 재해석하지 않는다.

## 예산·오류·재개

| 구분 | 계약 |
|---|---|
| 기존 extract 내부 배치 | 최대25조항 /24,000bytes(지침·정의·metadata·2,048 reserve포함)/요청당40배치 |
| 전체 plan 원본 | 최대10,000조항/직렬화16MB/최대500parts |
| part | planner가 기존 extract 내부1배치 예산으로 생성. 단일 과대 조항은 안전한 완결단위로 무손실 분리; 불가하면413 |
| 413 | `EXTRACT_INPUT_BUDGET_EXCEEDED`, `BULK_INPUT_BUDGET_EXCEEDED`, `BULK_UNSPLITTABLE_CLAUSE` 등. 같은 입력 반복 금지, 계획 경로/원문구조 조사 |
| 422 | 요청모델/계획 불일치. 같은 입력 무조건 재시도 금지 |
| 502 `BULK_RESULT_INVALID` | 병합 결과의 원문 인용/조항/구조 검증 실패. Gemini 서비스 오류와 구분하며 같은 결과 무조건 재시도 금지 |
| 409 `BULK_INCOMPLETE` | merge 필수part누락. 저장한 성공part는 재사용하고 누락/실패part확인 |
| 502 Gemini 오류 | `{code,message}` 유지. 503에 따른cooldown은 안전한 `Retry-After`헤더가 올 수 있음. 모든502가503/재시도가능이라는 뜻은 아님 |

원문 인용은 동일한 공백 정규화 후 부분문자열로 검증한다. 숫자/문자 유사도로 허용하지 않는다. 병합은 모든 part와 원 ID·인용을 재검증한다. 최종 extract validator는 부적절한 row를 제외할 수 있으므로 결과 수만으로 위험 조항이 없다고 단정하지 않는다.

503 생성은 논리 배치당 최초1+재시도2,SDK attempts=1,2~3/4~5초 jitter backoff,Retry-After,대기합계15초/총300초/시도90초 예산을 적용한다. 최종503 기본cooldown60초이며 더 긴 명시대기는 존중한다. 기존429재시도최대1은 전체시도상한 안에서 적용한다. extract504의 즉시 재시도를 새로 추가하지 않았다. explain은 별도 기존안정화 정책이다.

Spring은 원본 digest, model/code/rule version, selection_id/plan_id, mode/focus, 성공part응답, 실패·미실행범위, 시도횟수, cooldown을 보관하고 제한적 재개를 구현해야 한다. AI가 Spring 상태를 저장한다고 가정하지 않는다. 503/429가 내부에서 복구 중일 때 Spring 전체요청 반복을 중첩하지 않는다. 계정 한도 확인 전 병렬상한을 늘리지 않는다.

`POST /ai/extract/candidates/progress`는 동일원본/mode/focus/두ID + completed_part_ids/failed_part_ids를 받는다. 반환 PARTIAL/failed_ranges/pending_ranges는 **호출자가 제출한 ID 기반 참고 상태**이며 실제 결과 검증/영속저장이 아니다. 프론트 폴링은 Spring 저장소를 조회하고 매번 전체원문을 AI에 재전송하지 않는다.

## 배포 버전·역할 협의 필수

이번 candidate policy_version은 `recall-rules-2`이며 PR #17의 `recall-rules-1`과 다르다. 필드 계약은 같지만 selection/plan ID가 바뀐다. **구버전 계획과 신버전 merge를 섞지 말고** 진행 중 작업의 코드버전을 고정하거나 명시적 새 계획을 만든다. 호환되지 않는 기존 성공part를 자동 재사용하지 않는다. 원본은 그대로 보존한다.

팀원 구현이 이 명세를 따른다면 AI 경로와 호환된다. API 필드 변경은 이번 분리에 필요하지 않다. main `34bbc73`의 백엔드PR19는 후보wrapper/전용merge경로를 사용하지만 다음 의미적 주의사항이 확인됐다. 이번 AI PR에서 Spring 소스는 고치지 않는다.

- `AnalysisRunner`는 일부part HTTP실패를 빈 `ExtractResponse`로 대체해merge에 넣는다. AI는 이것을 진짜 성공한 빈 결과와 구분할수없다. 실패part를빈결과로제출하지말고미처리/PARTIAL로보관하고완료전에merge하지않도록 백엔드팀의 수정이 필요하다.
- `CandidateMergeResponse`는 coverage/selection_id/plan_id를무시한다. 후보범위미검토/NOT_FOUND를설명하려면 coverage를보관·표시하는백엔드처리가 필요하다.
- explain의 전체요청 실패후항목별재호출도남아있다. AI내부재시도와중첩되는호출량·시간예산을 백엔드팀과협의해야한다. 이AI PR이 해당정책을대체하지않는다.

이는 AI필드스키마호환과별개인 백엔드실패처리/상태정책이다. 팀원코드리뷰와양측HTTP통합에서해결여부를확인해야한다.

Compose/backend영속volume/AI_CHECKPOINT_DIR/PARTIAL·resume API/상태DB/인증·삭제는 공동설계 또는 백엔드담당이며 이번 AI전용 변경에 옮기지 않았다. AI 이미지만으로 팀원 Spring의413이 자동 해결된다고 보고하지 않는다.
