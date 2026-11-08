# PR #18 역할 분리 준비 결과

## 기준과 보존

- 원격 main: `49126ec6cb3d146b96d20ab5e0b4c354d0dc0e32` (PR #17 병합).
- 원래 PR #18: `fix/spring-lossless-extract`, `8d7c92748f2932be6ad63b98b585f9b83b75d3a1`,49파일. 원격 PR은 열린 상태/미병합으로 확인했다.
- 준비 브랜치: `fix/ai-extract-resilience`. 최신 main에서 별도 작업 트리를 만들었다. 기존 PR18/다른 작업 트리는 변경하지 않았다.
- 분리 준비 당시에는 로컬 미커밋 변경안으로 보존했다. 이후 사용자 요청으로 AI 전용 게시를 준비하며, backend/frontend/rootCompose 변경은 계속 포함하지 않는다. 기존PR18/운영배포/실제Gemini호출은 변경하지 않는다.

게시 준비 시 최신 main `34bbc73946dba8a3daf8409162fa8268ff23fe16`(백엔드PR19 병합)으로 fast-forward했다. 팀원이 반영한 backend13파일은 main 그대로이며 이AI변경안의diff에 포함하지 않는다. AI변경과 충돌은 없었다.

## 의존 관계

PR #17은 `bulk_extract.py`, `candidate_filter.py`, `extract_planning.py`, `bulk_http.py`, `bulk_parallel.py`, `extract_gate.py`, AI신규routes/테스트/이미지구성을 main에 도입했다. plan/candidates/merge 전체를 이번에 새로 만드는 것이 아니다.

PR #18은 그 위에 AI진단·후보미세조정·503복구와 Spring오케스트레이션/영속상태를 함께 추가했다. 새AI브랜치도 PR #17 병합 main을 기준으로 하므로 Python모듈/API가 빠지지 않는다. PR #18 전체커밋을 cherry-pick하면 Spring변경까지 따라오므로 하지 않았다. 변경된 `ai/`18파일만 그대로 가져오고 AI문서·범위테스트·AI전용ignore를 추가했다.

## 파일별 결정

**원래49파일 전체의 정확한 목록은 [PR18_FILE_INVENTORY.md](PR18_FILE_INVENTORY.md)에 있다.**

- AI유지18파일: app.py,extract_resilience.py,extract_planning.py,candidate_filter.py,bulk_http.py,bulk_parallel.py,AI Dockerfile/.dockerignore,해당테스트·AI도구·AI문서. `bulk_extract.py` 등 변경없는 PR17 모듈도 main으로부터 유지된다.
- 백엔드제외25파일: AnalysisRunner/AiClient/AiModelIdentity/BulkExtractCoordinator/DurableExplainCalls,AnalysisController/Service/Store,DocumentStore,persistence모듈,application.yml,backend Dockerignore와 Java테스트/설정. 삭제하는 것이 아니라 **새AI변경안으로 옮기지 않는다**. 원래PR18에 보존했다.
- 공동협의2파일: 루트 .gitignore와docker-compose.yml. Spring디스크영속volume/AI_CHECKPOINT_DIR/실행정책을 AI담당이 임의로 옮기지 않는다. 필요한 민감정보제외는 기존규칙을 보존한 ai/.gitignore에만 보완했다.
- 문서분리4파일: DURABLE_RECOVERY/EXTRACT_413_GUIDE/PR_VALIDATION/SPRING_INTEGRATION. Spring설명·실제전체미검증 이력은 원래PR18에 보존하고, 이번에는 [AI API 계약](README_extract_api_contract.md)과 이 분리문서로 대체한다. 기존 main의 integration문서는 건드리지 않는다.

## 호환성 확인

- main과 분리안의 OpenAPI 요청/응답 스키마 및10경로 계약 동일:health,classify,extract,explain,전체plan/merge,후보select/plan/merge/progress.
- 후보경로: `{source_request,mode,focus_items}` → `{selection,candidate_plan}` → candidate_plan.parts[].request를extract → 후보전용merge → `{extractions,coverage,selection_id,plan_id}`.
- 전체경로: ExtractRequest → 전체plan → parts[].request를extract → 전체merge → `{extractions}`. 둘다유지하며혼용은422로거부한다.
- 새후보규칙 `recall-rules-2`는기존rules-1과selection/plan해시가달라진다. 스키마호환과기존체크포인트재사용호환을구분한다. 진행작업의AI버전고정 또는새계획이필요하다.
- `BULK_RESULT_INVALID`는기존HTTP502이며Gemini503과다른비재시도성검증실패다. 인용오류를구별하려고응답코드를바꾸지않았다.
- 팀원의미게시Spring구현은검증할수없다. wrapper/coverage,원본/mode/focus/ID보존,오류분류,PARTIAL/NOT_FOUND표시를명세대로구현했는지팀원리뷰및HTTP통합이필요하다.

## 검증

기존304개와신규AI단독계약8개를함께실행해 **312개 모두통과**했다(2026-11-08). 신규범위는후보part실행/merge,전체plan유지,혼용차단,누락409,원문/모드변경422,인용오류502,progress참고상태이다. Spring모듈을가져오지않아도실행가능한합성fixture이며실제Gemini0회이다.

413단일조항/40배치/무손실분할·원문검증과503최대3시도/SDK자동retry차단/Retry-After/cooldown 회귀는기존AI테스트에포함한다. Spring413해결은팀원이올바른분할경로를실행해야완성되며AI이미지변경만으로보장하지않는다.

AI전용작업트리에서기존New Dental 실제JSON의오프라인감사도통과했다:1,472조항→1,479fragment→77parts,최대25조항/23,945bytes,전Mock part200/merge200/누락merge409,primary원문·metadata·SHA보존. 직접전체extract는기존37,356byte단일조항413을그대로재현한다. 이감사는PDF대조/실제Gemini정확도검증이아니며원문JSON과결과는Git밖에보존했다.

## PR #18을 정리하는 가장 안전한 순서 (아직 실행하지 않음)

1. PR18을그대로보존하고백엔드팀원과파일소유권/후보API계약을합의한다. 담당자작업을덮거나기존PR을바로force-push하지않는다.
2. 로컬AI전용diff를리뷰한뒤사용자승인시별도AI전용PR을만든다. 이때backend/frontend/Compose변경0개인지다시확인한다.
3. 팀원의Spring후보plan/merge구현과AI전용브랜치를통합검증한다. 기존Spring전체분석오케스트레이터를중복도입하지않는다.
4. 체크포인트/재개/삭제/보관기한/volume은백엔드소유의별도PR로선택적으로설계한다. PR18코드는참고자료로만사용한다.
5. 대체AI PR및백엔드책임이확정된뒤PR18에후속링크를남기고미병합종료하는방안을추천한다. 종료/변경은사용자·팀합의후진행한다.

실제전체77Gemini/React최종E2E,Docker/Java21실환경은이작업의검증완료범위가아니다. 무료RPD20으로전체77생성을강행하지않는다. 모델/.env/B안OFF/실제약관자료는그대로유지한다.
