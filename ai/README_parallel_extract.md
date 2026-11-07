# 제한적 병렬 extract — 로컬 구현, 실제 Gemini 미검증

기존 `run_bulk` 순차 함수와 API JSON은 변경하지 않았다. 새 opt-in 실행기
`bulk_parallel.run_bounded_bulk`는 기본 concurrency=1이며 2~4를 명시적으로 선택할 수 있다.
전체 무손실 source/plan과 후보 source/candidate_plan에 동일하게 사용한다.
Spring·프론트는 수정하지 않았고 운영 배포·Gemini 호출·커밋·푸시·PR도 하지 않았다.

## 실행 계층

```python
from bulk_parallel import run_bounded_bulk, ParallelCheckpoint
from bulk_http import HttpPartSender

checkpoint = ParallelCheckpoint(plan['plan_id'])
# 실제 네트워크 호출 예시: 명시적으로 승인된 실행에서만 사용할 것.
with HttpPartSender('http://127.0.0.1:8001') as send:
    merged, provenance = run_bounded_bulk(
        source, plan, send, checkpoint, concurrency=1)
```

이 실행기는 HTTP caller 라이브러리이다. FastAPI 내부에 상태 저장 job API를 추가하지 않는다.
Spring은 아래와 같은 worker/checkpoint 정책을 별도로 구현해야 한다.
기존 Java 제안 helper 역시 아직 순차 구현이며 이 Python 파일을 추가했다고 병렬로 바뀌지 않는다.

- worker당 future 1개, 최대 concurrency개만 제출. 끝난 자리를 다음 part로 채운다.
- 한 프로세스 최대 8 job, job당 최대 4 worker, 공유 admission의 실제 HTTP 동시 실행 최대 4.
- 같은 plan을 동시에 두 번 실행하지 못하도록 등록한다. 입력/계획 검증 후 변경하지 않아야 한다.
- sender에는 request 복사본을 주며 worker별 HTTPX client를 사용한다. HTTP transport retries=0.
  HTTP 연결/클라이언트 timeout은 결과가 불명확하므로 자동 재시도하지 않는다.
- 기존 SDK는 동기 generate_content이다. async SDK/event loop 혼합은 도입하지 않았다.
  설치된 동기 SDK의 공유 client+MockTransport에서 동시 요청 옵션 분리를 검증했지만
  vendor 서비스의 실제 동시 호출 가용성/안전성을 증명한 것은 아니다.

## AI 서버 extract gate

`AI_EXTRACT_CONCURRENCY` 기본 1, 허용 1~4. 잘못된 값은 안전하게 1을 사용한다.
같은 AI 프로세스의 모든 call_extract_batch(B안 경로 포함)가 공유 gate를 사용한다.
원래 함수의 rate limiter, SDK 호출, 429/503 재시도 및 오류 변환은 그대로이다.
gate는 호출 제한 대기와 재시도 백오프까지 포함해 slot을 유지한다. 보수적인 설계다.
대기 상한 30초(재확인 deadline이 더 짧으면 그 값), 초과는 기존 timeout 502 경로이다.
explain/classify의 생성 흐름·timeout/retry 설정은 건드리지 않았다.

클라이언트 concurrency=2여도 서버 gate=1이면 SDK 실행은 직렬화된다.
기본 설정은 계속 1이며 실제 2개 병렬 실험은 별도 테스트 프로세스에서 양쪽을 2로 설정해야 한다.
실제 Gemini 검증 전 운영값을 3~4로 올리지 않는다.

## 호출량/할당량 보호

기본 caller admission은 공유 동시 HTTP 최대 4, 시작 5회/60초(12초 간격), rolling 24시간
HTTP 예약 최대 200회이다. extract의 SDK generation 최초+재시도 최대 2회라는 현재 정책을
고려한 보수적 시작 간격이다. 서버의 기존 generation 10회/60초 제한도 재시도에 적용된다.
모델 조회는 별도이며, classify/explain 및 다른 앱·키 공유자의 사용량은 이 caller가 알지 못한다.
일일 한도 초과는 요청 간격이나 동시성을 낮추는 것만으로 해결되지 않는다. 자동 전체 반복은 하지 않는다.

429/503/504 또는 502 GEMINI_API_ERROR/GEMINI_TIMEOUT이 관찰되면 공유 admission을 1로 낮추고
2·4·8…최대60초 cooldown을 적용한다. 외부 429에 Retry-After가 있으면 이를 우선 반영한다.
성공 2회 후 cooldown이 끝났으면 한 단계씩 회복한다. 이것은 **새 요청 시작 지연**이며
외부 HTTP 자동 재시도가 아니다. 내부 SDK 재시도 정책을 늘리지 않는다.
AI가 내부 재시도로 복구해 HTTP 200을 반환한 경우 caller는 그 503을 알 수 없다.

TPM 제한은 optional estimated_tpm이다. 입력 예산 바이트+기본 output reserve 8,192를
보수적 추정으로 예약하며 실제 토큰 수·thinking/output 상한을 보장하지 않는다.
계정 TPM/RPM/RPD 확인 및 실제 usage 집계 없이 TPM 준수/무료 사용을 보장하지 않는다.
동시성은 호출 수 자체를 줄이지 않는다. 세 문서 전체는 정상 생성 265회, 후보 범위는 233회이며
재시도·fallback·explain이 추가된다. RPD 500인 계정도 전체 재시도 시 한도 초과가 가능하다.
따라서 기본 caller 200회 예약 상한으로는 세 문서 전체를 같은 24시간에 완료하지 못할 수 있다.
실제 계정의 승인된 사용 예산에 맞춰 admission 설정이나 실행 일정을 정하고, 상한을 조용히 해제하지 않는다.

모든 제한은 **해당 프로세스 로컬**이다. 재시작하면 caller 카운터가 초기화된다.
AI worker 여러 개·여러 Spring/PC가 같은 키를 쓰는 경우 중앙 limiter/할당량 저장소가 필요하다.
초기 실제 실험은 AI worker 1개에서 진행한다.

## 실패·취소·체크포인트

- 성공 응답을 개별 검증한 뒤 coordinator에서 checkpoint에 저장한다. 최종 병합은 원래
  계획 순서로 실행하므로 완료 순서가 바뀌어도 결과/원본 ID/위치가 유지된다.
- 실패를 관찰하면 새 작업 투입을 중단한다. 이미 시작된 작업은 기다려 성공을 저장한다.
  동시에 영구 오류와 일시 오류가 오면 영구 오류를 우선해 무조건 재개를 방지한다.
- 413/422/인용 검증 실패는 동일 입력 재개 금지. 성공 부분은 다시 요청하지 않는다.
  일시 오류도 자동 전체 재시도하지 않으며 기존 part당 누적 2회/HTTP 총600회 예산을 유지한다.
- cancel Event 또는 Ctrl+C는 새 시작을 중단하고 in-flight를 drain한다. 강제 thread/socket
  취소는 하지 않는다. sender가 timeout을 지켜야 한다. 네트워크 timeout/240초 예산은 강제
  OS 종료 타이머가 아니며 job 4시간 예산도 기존처럼 cooperative이다.
- 늦게 온 유효 성공은 checkpoint에 남기되 전체 작업 예산을 넘기면 성공으로 반환하지 않는다.
- `checkpoint_payload`/`restore_checkpoint`로 잠금 없이 직렬화 가능한 payload를 사용한다.
  기존 순차 Checkpoint는 `upgrade_checkpoint(old)`로 명시적으로 복사 이관하고 반환된 새 객체를
  보관한다. 성공 결과·계획 해시·누적 예산은 유지하며, 결과 불명확한 기존 in-flight는 재시도하지 않는다.
  중간 snapshot에는 현재 elapsed를 포함한다. crash 시 in_flight가 남아 있으면
  interrupted_unknown/BULK_CLIENT_ERROR로 바꿔 결과 확인 없이 재전송하지 않는다.
- checkpoint에는 실제 인용이 포함된다. 로그/Git에 저장하지 말고 기존 권한/F-21/암호화·
  내구성 저장 정책을 적용한다. Python 메모리 객체만으로 운영 영속성을 제공하지 않는다.
- 부분 결과를 전체 200으로 반환하지 않는다. 후보 PARTIAL/실패 범위는 기존 progress 계약과
  checkpoint.states의 part ID/코드를 사용하되 완료 결과의 진위는 merge에서 검증한다.

## Mock 성능 해석

실제 3개 JSON에 대해 full/candidates 각각 concurrency=1,2,3,4를 비교했다.
요청마다 30ms Mock I/O 지연을 사용했으며 **비교에서 quota pacing/cooldown을 비활성화**했다.
운영 기본 limiter와 서버 gate가 적용되는 실제 Gemini 성능 결과가 아니다.
모든 요청 성공 시 HTTP 재시도는 0, 최대 in-flight는 각각 1/2/3/4였고 직렬 결과와 일치했다.
각 문서의 별도 503 실험에서는 시작한 2개 중 성공 1개를 보관하고 실패 후 새 요청을 중단했다.
명시적 재개 시 성공 1개는 건너뛰었다. 자세한 수치는 integration 문서 참고.

## 오프라인 테스트

```powershell
python -m unittest -q test_bulk_parallel
```

원문 보존·계약·기존 테스트는 전체 suite에서도 검증한다. Docker/실제 Spring 및 vendor API
병렬 테스트는 별도이다. 기존 Spring의 전체 요청 무조건 재시도/5분 block과 장시간 폴링 문제는
이 worker 구현만으로 해결되지 않는다.
