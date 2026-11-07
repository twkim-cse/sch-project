# 후보 조항 사전 선정 — 재현율 우선의 선택적 검색 범위

이 기능은 별도 `feat/lossless-large-policy` 작업 트리에만 구현했다. 실제 Gemini 호출,
운영 배포, Spring/프론트 변경, 커밋/푸시/PR은 하지 않았다. 모델과 .env, B안 OFF는 유지한다.

## 기존 경로와의 관계

기존 `/ai/classify`, `/ai/extract`, `/ai/explain` 및 무손실 `/ai/extract/plan`, `/ai/extract/merge`는
바꾸지 않았다. **기존 경로에서는 계속 모든 입력 조항을 분석 대상으로 보존한다.**
후보 필터는 아래 별도 경로에서만 명시적으로 선택한다. 기본 운영 흐름을 몰래 필터링하지 않는다.

전체 원본 → 후보 선정 → candidate_request → 기존 plan_bulk → 순차 extract → 후보 범위 병합.
원본은 그대로 유지하며, 후보의 모든 필드는 원본과 동일하다. 과대 후보는 기존 무손실 분할을 사용한다.

## 규칙

검색에만 공백 제거/casefold를 적용한다. 실제 전송 text는 변경하지 않는다.
본문 직접 표현 6점, 본문 다중 신호 4점, 제목 직접 표현 3점, 제목/section+본문 결합 2점,
문맥·애매한 표·명시적 broad 검색 1점이다. 이 점수는 확률이나 Gemini 정답 번호가 아니다.

| 항목 | 직접·복합 신호 예 |
|---|---|
| 1 | 면책·부담보·지급하지 않음·보장 제외, 보험금/보장 + 제한/고의/자살 |
| 2 | 면책기간·감액·보장/책임개시·지급률·한도, 지급/보장 + 기간/비율/횟수 |
| 3 | 갱신 보험료·보험요율·재산정·변동, 갱신/재가입 + 보험료/연령/조건 |
| 4 | 알릴/고지/통지의무·위반 효과, 청약/계약 + 사실대로 답변/변경 통지 |
| 5 | 진단확정·질병분류·관혈·조직검사·치아우식, 질병/수술/입원/절제 등 + 정의/인정/범위 |
| 6 | 환급금·해약반환금·미경과보험료·보험계약대출, 해지/대출/적립액 + 지급/공제/권리 등 |

- 제목이 틀려도 본문으로 선정한다. 제목만 근거여도 원문이 의미 있는 경우 애매한 후보로 포함한다.
- 표 표시가 있으면 신호가 약해도 포함한다. 표 표시가 빠진 짧은 숫자·보험금액도 보수적으로 포함한다.
- 고립된 예외 문구를 포함하고, 같은 rider/section/article의 앞뒤 문맥을 한 단계 확장한다.
  12개 이하의 연속 조문 묶음은 함께 포함하되, 잘못된 제44조 같은 거대 묶음 전체로 확장하지 않는다.
- '보험' 한 단어만으로 전 항목 후보로 넣지는 않는다. 후보 개수 상위 N개 제한도 두지 않는다.
- 의료기관·감염병 정의 등 제목에 전문 범위가 있으면 본문이 간접 표현이어도 포함한다.
  짧은 준용/참조 규정도 근거가 불충분한 후보로 남겨 원 조항을 확인할 수 있게 한다.
- 확실한 점선 목차/제목+쪽수/문장 없는 구두점은 제외한다. 숫자 하나를 무조건 목차로 판단하지 않는다.
- 법규 section 이름만으로 제외하지 않는다. 형사 법규 범위 + 처벌 내용 + 보험/의료 직접 근거 없음이
  함께 확인될 때만 명백히 무관한 후보로 분류한다. 환급금 권리·소멸시효 등의 법규는 유지한다.
- 선정 번호는 **검색 태그**이며 기존 EXTRACT_INSTRUCTION의 최종 분류 지침을 바꾸지 않는다.
  특히 후보 2번의 지급률/보장한도는 기간 한정인 기존 2번보다 넓은 검색이다. 이를 최종 분류 범위로
  확장할지는 기능명세서와 Spring 팀의 별도 합의가 필요하다. 대출 후보도 기존 6번 후처리를 그대로 받는다.

## API: 모두 무상태이며 Gemini를 호출하지 않음

공통 입력:

```json
{
  "source_request":{"analysis_id":"...","check_items":[1,2,3,4,5,6],"clauses":[]},
  "mode":"recall",
  "focus_items":null
}
```

- `POST /ai/extract/candidates`: candidate_request, decisions, selection_id, coverage 반환.
  decisions에는 원래 clause_id/source_index, 관련 check_items, 점수·정적 reason 코드,
  미선정 사유가 있다. 원문을 서버 로그에 기록하지 않는다.
- `POST /ai/extract/candidates/plan`: selection(추적 명세), candidate_plan(parts/fragments/plan_id) 반환.
  candidate_plan의 part.request를 기존 `/ai/extract`로 순차 전송한다.
- `POST /ai/extract/candidates/merge`: 위 공통 입력 + selection_id + plan_id +
  part_results[{part_id,response}]를 보낸다. 모든 part 결과를 엄격히 검증한 뒤
  `extractions`와 `coverage`를 반환한다. 후보 일부가 빠지면 409이며 부분 결과를 200으로 반환하지 않는다.
  **기존 bulk merge 대신 이 경로를 사용해야 한다.** 전체 원본과 후보 계획은 분석 범위가 다르다.
- `POST /ai/extract/candidates/progress`: 공통 입력 + selection_id + plan_id +
  completed_part_ids + failed_part_ids. 범위·중복 ID를 검증하고 실패/대기 part의 원래 clause_id를 반환한다.
  이 경로는 백엔드 체크포인트의 **신고 값에 대한 스냅샷**이며 결과 본문을 검증한 증명이 아니다.

후보 범위 merge는 기존 extract와 달리 coverage를 포함한다. 성공 시 `CANDIDATES_COMPLETE`,
`all_candidates_completed=true`이지만 `recall_verified=false`, `risk_absence_determined=false`이다.
전체 원문을 실제 모델이 처리한 것이 아닌데 DONE/전체 분석 완료로 위장하지 않는다.
full_document_analyzed는 선택 범위의 전송 완료이고, 모든 위험 탐지 성공을 뜻하지 않는다.

## fallback / 누락 위험

- recall: 기본 다중 신호·명확한 표현·문맥. 항목별 근거 0개면 그 항목의 broad 규칙을 자동 재검색한다.
- broad: 해당 항목의 약한 용어도 포함한다. `focus_items:[3]`처럼 요청한 번호만 대상으로 넓힐 수 있다.
- full: 확실한 목차/법규까지 포함해 모든 원본을 선택한다. 기존 무손실 전체 경로로 돌아갈 수 있다.
- 근거/후보 0개 또는 Gemini 추출 0개는 '실제 관련 조항 없음'의 증거가 아니다.
- Gemini 결과에서 항목이 비면 Spring이 broad/full을 명시적으로 선택할 수 있다. 이것은 B안 자동
  Gemini 재확인을 켠 것이 아니다. 이 모듈은 Gemini 호출 없이 검색 범위를 결정할 뿐이다.
- mode/focus/원본/선정 정책이 바뀌면 selection_id와 plan_id가 바뀐다. 새 체크포인트를 사용한다.
  같은 계획에서만 기존 성공 part를 재사용한다. 넓힌 fallback은 이전 조항을 재전송할 수 있어 비용이 늘 수 있다.
- 일반어만 있는 조항, 참조만 있는 조항, 잘못 이어진 문단, 표의 단독 값, 새로운 의학 약어 등은
  누락 가능성이 남는다. 결정 파일의 no_rule_evidence_unreviewed를 사람이 표본 점검해야 한다.

## PARTIAL / Spring 협의

동일 계획의 기존 run_bulk·체크포인트·실패 즉시 중단·시도/시간 예산을 그대로 재사용한다.
진행 스냅샷은 성공+실패 부분이 함께 있으면 PARTIAL, 전부 실패면 FAILED, 전부 완료면
CANDIDATES_COMPLETE를 나타낸다. 실패 부분의 검증되지 않은 결과나 해설은 제공하지 않는다.
413/422의 무조건 재시도는 없으며 503/504/explain 안정화 설정을 변경하지 않는다.

Spring의 현재 AnalysisStatus는 QUEUED/RUNNING/DONE/FAILED만 있다. PARTIAL을 기존 응답에 바로
추가하면 프론트 검증과 충돌할 수 있다. 기존 status=FAILED를 유지하면서 별도 processing_detail
또는 새 진행 API로 PARTIAL을 제공하는 방안을 먼저 협의한다. 기존 frontend 코드는 바꾸지 않았다.

세부 수정 위치와 제안 계약은 `integration/spring-bulk/CANDIDATE_INTEGRATION.md` 참고.
종전 spring-bulk.patch는 **무필터 전체 분석용**이며 후보 경로용으로 자동 적용되지 않는다.

## 오프라인 실행

```powershell
python -m unittest -q test_candidate_filter
python tools/candidate_dry_run.py --data <원본_request.json>
python tools/candidate_dry_run.py --data <원본_request.json> --mode broad --focus 4
```

원본/결정 명세/결과는 Git 제외 경로에서만 다룬다. 모델 키·약관 원문·인용/해설을 로그에 쓰지 않는다.
실제 비용·503 감소·분류 정확도는 오프라인 호출 수 비교만으로 검증되지 않는다.
