# 대용량 extract: 무손실 분할 계획과 전체 결과 병합

## 상태와 범위

이 변경은 별도 로컬 `feat/lossless-large-policy` 작업 트리의 제안 구현이다.
기존 실행 중인 AI 서버·Spring·프론트를 변경하거나 재배포하지 않았다. 실제 Gemini 호출은 하지 않았다.
기존 `/ai/classify`, `/ai/extract`, `/ai/explain` 요청·응답 및 Gemini 설정은 그대로이다.
기존 extract의 25개 조항/24,000바이트/40배치 제한도 유지한다. B안은 OFF이다.

전체 JSON이 40배치를 초과하거나 조항 하나가 예산을 초과하면 기존 extract는 413을 반환한다.
제한을 늘리는 대신 추가 **무상태** 계획·병합 API를 사용하고, Spring이 기존 비동기
AnalysisRunner 안에서 작은 `/ai/extract` 요청들을 순차 실행하도록 한다.

## 흐름 / API

1. `POST /ai/extract/plan`: 기존 ExtractRequest 그대로 전달한다. Gemini/모델 조회 없이
   `plan_id`, `source_digest`, `parts[{part_id,input_bytes,request,context_reasons}]`,
   `fragments`, 보존 검증·문맥 지표를 반환한다.
2. 각 part.request를 기존 `POST /ai/extract`로 한 번씩 보낸다. 각 요청은 25개 이하이고
   공통 지침·모든 메타데이터·JSON 이스케이프·2,048바이트 여유를 합쳐 24,000바이트 이하다.
   계획은 입력 순서를 유지하고, 같은 계약/section/article의 연속 조항 묶음이 들어가면 함께 보낸다.
3. Spring은 성공한 part_id/응답을 체크포인트에 저장한다. 실패하면 즉시 중단하고 FAILED로
   처리한다. 성공한 일부 결과를 DONE이나 HTTP 200의 전체 결과로 위장하지 않는다.
4. `POST /ai/extract/merge`:

```json
{
  "source_request": {"analysis_id":"...","check_items":[1,2,3,4,5,6],"clauses":[]},
  "plan_id":"...",
  "part_results":[{"part_id":"...","response":{"extractions":[]}}]
}
```

   서버가 원본으로 계획을 재생성한다. 계획 ID·모든 part 완료·요청 범위·child ID·원문 인용을
   검증하고 원래 clause_id로 복구한 뒤 기존 원문/6번 후처리 검증을 수행한다.
   정상 응답은 기존 `{"extractions":[{"check_item":1,"clause_id":"...","quote":"..."}]}`이다.
   누락된 part는 409 `BULK_INCOMPLETE`, 다른 계획은 422 `BULK_PLAN_MISMATCH`,
   잘못된 결과는 502 `BULK_RESULT_INVALID`이다. 모든 오류는 안전한 code/message 형식이다.

## 원문 보존 / 과대 조항

- 들어가는 조항은 모든 필드와 ID를 그대로 보낸다. 과대 조항만 임시 ID로 나누며
  `fragments[child_id]`가 원래 ID, source start/end, primary_start/end를 기록한다.
- 문자 위치는 **원본 text의 Python Unicode 코드포인트 기준**이다. PDF 좌표·실제 페이지
  경계·Java UTF-16 index와 같지 않다. page는 원본 값이며 없는 PDF 페이지를 만들어내지 않는다.
- 공백까지 포함한 원본 문자열 슬라이스만 사용한다. primary 범위가 0부터 끝까지 정확히
  한 번 이어지는지, 재조립 문자열이 원본과 같은지, 나머지 메타데이터가 같은지 검사한다.
- 완전한 한국어 서술 문장, 빈 줄 문단, 3개 이상 연속 번호로 구조가 확인된 목록 항목
  경계에서만 분할한다. 고정 글자 수/유사도/요약/중간 자르기는 사용하지 않는다.
- primary는 보수적인 14,000바이트 입력 예산으로 만들고, 가능한 경우 앞뒤 완전한 단위의
  문맥을 24,000바이트 안에서 복사한다. 복사 범위와 이유를 기록한다.
- 같은 조의 다음 요청이 '다만/예외/전항' 등으로 시작하면 앞 조항을 예산 내에서 복사하고
  `context_reasons`에 기록한다. 들어가지 않으면 누락 사실을 지표로 남긴다.
- 번호 목록에서 나눈 조각은 `boundary_context_review_required=true`이다. 공통 머리말이나
  멀리 있는 예외가 모든 조각에 들어간다고 보장하지 않는다. 조문 분리가 잘못된 입력은
  원문을 보존해도 의미가 손상될 수 있어 백엔드/PDF 대조가 여전히 필요하다.
- 완전한 단위 하나도 들어가지 않으면 413 `BULK_UNSPLITTABLE_CLAUSE`로 **전체 계획 실패**.
  예산을 해제하거나 그 조항을 버리지 않는다. 백엔드가 PDF 문단/표 구조를 복구해야 한다.
- 동일 인용이 여러 위치에서 반복되면 merge_bulk의 provenance에 가능한 위치를 모두
  기록하고 ambiguous_location을 표시한다. public extract 계약은 위치 필드를 추가하지 않는다.
  같은 원본 ID·점검 번호·정규화 인용은 결과 한 개로 합치되 provenance는 모은다.
- 원문 공백 비교 외에는 숫자·글자를 변경한 인용을 허용하지 않는다. 서버가 검증한 부분문자열은
  해당 점검 번호의 의미적 정답을 보장하지 않는다.

## 호출·시간·재개 제한

- 전체 입력 10,000개/직렬화 16MB, 계획 최대 500 요청. 병합 결과 입력은 32MB 제한.
  이 수치는 파싱 후 검증이므로 리버스 프록시의 본문 크기/메모리 제한도 별도 설정해야 한다.
- `run_bulk`는 sender 콜백을 순차 실행하고 첫 오류에 중단한다. 성공한 응답만 체크포인트에 저장한다.
- 작업 처리 시간 합계 최대 4시간, 요청당 270초 이하, part당 누적 최대 2회,
  작업 전체 HTTP extract 시도 최대 600회. 모델 목록·계획·병합 요청은 Gemini 생성 수와 구분한다.
- 외부 HTTP 요청을 자동 재시도하지 않는다. 기존 AI extract의 429/503 최초+1회 정책만 사용한다.
  명시적 재개 시에만 429/503/504 또는 502의 GEMINI_TIMEOUT/GEMINI_API_ERROR를 재확인한다.
  413/422·검증 실패·모호한 연결/클라이언트 오류는 자동 재개하지 않는다.
- 재개는 같은 plan_id여야 하며 성공한 part는 건너뛴다. 입력·지침·분할·문맥 정책이 바뀌면
  ID가 달라져 기존 결과를 섞지 않는다. 명시적 재개도 시도/시간 예산을 공유한다.
- sender는 전달받은 timeout을 실제 전송에 적용해야 한다. Python callback을 강제로 취소하지 않는다.
- explain 안정화(60/45/240초, 요청 전체 추가 재시도 2회)는 그대로이다. main 603ef62의 Spring은
  전체 explain 실패 후 check_item별 fallback을 추가했지만, 그룹 내 항목 수 제한은 없다.
  1~2개씩 나누는 정책과 검토/QUOTE_ONLY 처리는 별도 협의가 필요하다.

호출량은 각 part의 정상 generateContent 1회, 기존 재시도까지 최대 2회이다. 10회/60초의
프로세스 공유 제한을 유지한다. 여러 worker/서버를 쓰면 분산 한도는 별도 필요하다.
77~100개 요청은 10~30초/호출의 단순 시나리오만으로 약 13~50분이며 실제 측정값이 아니다.
세 문서를 함께 처리하면 RPD가 500인 계정에서는 상당 부분을 쓰며 내부 재시도·explain은 별도다.
모델/계정의 실제 RPM/RPD·토큰 요금은 확인하지 않았다. 실제 비용은 해당 계정 정책에 따라 확인해야 한다.

## Spring 제안 패치 — 아직 적용하지 않음

`../integration/spring-bulk/`에 Java helper, 오프라인 HTTP 스텁 테스트, `spring-bulk.patch`를 준비했다.
실제 backend/ 또는 frontend/ 파일은 변경하지 않았다. `git apply --check`는 통과해야 하며
팀원이 변경한 후에는 다시 대조한다. helper는 다음을 구현한다.

- 기존 AnalysisRunner의 EXTRACTING/폴링 상태를 유지하면서 계획→순차 호출→병합
- bulk 전용 WebClient 복제본의 응답 메모리 예산(40MiB), 요청별 270초/계획·병합 30초
- 단일 활성 job Semaphore(대기 30초), 원래 5분 전체 extract block과 무조건 재시도 제거
- 최대 32개의 process-local 체크포인트(각 32MB), 24시간 lazy retention, 성공 시 삭제
- 실패 후 같은 analysis_id를 사용한 명시적 재개 시 성공 part 생략

**협의/추가 구현 필수:** 체크포인트의 권한 확인·명시적 문서 삭제(F-21) 연동·내구성 저장소·
인증된 재개 API·프록시/비동기 작업 시간 제한·운영 로그/진행도 지표·여러 서버 간 호출 제한.
현재 backend는 FAILED 후 같은 ID로 재개하는 UI/API가 없으므로 helper만 넣어도 자동 복구되지는 않는다.
main 603ef62의 AnalysisRunner.extract는 전체 실패 후 번호별로 전체 clauses를 다시 보내며,
일부 번호만 성공하면 나머지 실패를 미검출로 취급한다. 이는 이 제안의 엄격한 전체 완료 정책과 다르다.
연동 시 fallback/NOT_FOUND 정책을 함께 조정해야 하며 이 PR은 실제 Spring 코드를 바꾸지 않는다.
장시간 EXTRACTING 상태는 기존 프론트의 2.5초 폴링으로 조회할 수 있으나, 실제 브라우저·
프록시·Spring 비동기 executor·컨테이너 자원 한도와 체크포인트의 전체 메모리 상한은 통합 검증이 필요하다.
기존 extract에는 전체 시간 예산이 없어 270초 HTTP 대기보다 AI 내부 처리(조회·호출 제한 대기·재시도)가
길어질 수 있다. 애매한 클라이언트 timeout은 자동 재개하지 않으며, 취소·작업 식별 정책은 별도 협의한다.
계획 자체가 413인 경우 아무 생성도 하지 않으며 잘못된 원문 분리를 먼저 수정해야 한다.
Java 제안은 실제 Java 21/Gradle 환경에서 테스트한 뒤 병합해야 한다. 이번 환경의 JDK는 23이고
Gradle 오프라인 캐시/Java 21/Docker가 없으므로 Java·Docker 검증 성공을 주장하지 않는다.

## 오프라인 사용

```powershell
python -m unittest -q test_bulk_extract
# 원문을 콘솔에 출력하지 않는 계획/재조립/Mock 검증
python tools/bulk_dry_run.py --data <실제_request.json> --mock
```

실제 데이터 없이 기본 테스트가 실행된다. `--plan-out local_policy/plan.json`은 원문을 포함하므로
명시적으로 요청할 때만 Git 제외된 local_policy 아래에 저장한다. 이 도구에는 실제 API 호출 기능이 없다.
