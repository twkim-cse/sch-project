# Spring 담당자 검토용 제안 — 미적용

현재 backend/와 frontend/는 수정하지 않았다. AI 추가 경로와 Python 참조 orchestration은
ai/에 구현했지만, 기존 Spring AiClient는 여전히 전체 입력을 1회 전송한다.
**이 패치를 적용하지 않으면 기존 전체 입력의 413은 계속 발생한다.**

이 폴더의 Java helper/테스트를 패치로 적용하면 AiClient.extract가 기존 5분 전체 대기+
전체 요청 재시도를 사용하지 않고, 기존 @Async AnalysisRunner 안에서 계획→순차 extract→
완료 체크포인트→엄격 병합을 실행한다. explain HTTP 호출 방식은 이 패치에서 바꾸지 않는다.

검토 후 최신 main의 저장소 루트에서:

```powershell
git apply --check integration/spring-bulk/spring-bulk.patch
# 팀원 변경과 대조하고 합의한 후에만:
git apply integration/spring-bulk/spring-bulk.patch
cd backend
.\gradlew.bat test --tests '*BulkExtractCoordinatorTest'
```

Java 21과 Gradle 의존성이 필요하다. 이 환경에는 Java 23만 있고 Java 21/Gradle 캐시/Docker가
없어 Java 테스트를 실행하지 못했다. patch 적용 가능성만 확인했으며 컴파일 통과로 간주하면 안 된다.

필수 협의:

1. AI에 새 모듈과 Dockerfile을 함께 반영하고 `/ai/extract/plan`, `/ai/extract/merge` 등록 확인.
2. 체크포인트를 사용자의 기존 분석 권한/문서 보관 및 삭제(F-21)에 연결. 지금은 bounded process-local
   저장소 제안이며 여러 인스턴스·재시작을 견디는 내구성 저장소가 아니다.
3. FAILED 분석을 같은 analysis_id로 재개하는 인증된 API/관리 절차를 추가. 새 ID를 만들면 체크포인트를
   재사용하지 않는다. 프론트에는 현재 자동 재개 버튼이 없다. helper가 알아서 전체 재시도하지 않는다.
4. 기본 EXTRACTING 폴링은 그대로 쓸 수 있으나 긴 작업 executor·프록시·컨테이너 메모리/보관 시간을 검증.
5. 413/422/원문 검증 실패는 그대로 실패 처리. 503/504의 실제 Google 생성 재시도는 AI 내부 정책 유지.
   명시적 재개는 실패 part부터 시도하되 part당 최대 2회, 총 extract HTTP 최대 600회, 처리 시간 합계 4시간.
6. 불명확한 클라이언트 timeout/연결 종료 시 AI 작업이 계속될 수 있어 자동 재개하지 않음.
7. main 603ef62는 전체 explain 실패 후 번호별 fallback을 추가했다. 그룹 크기는 무제한이므로
   여전히 대량 결과에서 240초/32회 예산에 걸릴 수 있어 1~2개 단위 정책 협의가 필요하다.
8. API key/.env/원문/결과를 로그나 Git에 넣지 않기. 진행도에는 수치·단계·안전한 코드만 기록.
9. 원본 메타데이터/법규 혼입/목차 문제는 이 분할로 고쳐지지 않음. 문맥 경계 review 지표를 검토하고
   대표 조항 의미 정확성은 실제 API 테스트 승인을 받은 후 별도로 확인.

자세한 예산·원문 좌표·알려진 한계는 `ai/README_bulk_extract.md` 참고.

## 최신 main과의 협의 사항 (603ef62)

AnalysisRunner.extract의 번호별 fallback은 같은 analysis_id로 다른 check_items의 계획을 요청한다.
기존 실패 checkpoint가 있으면 제안 helper는 이를 계획 불일치로 거부한다. 서로 다른 계획의
부분 결과를 섞지 않는 보호 동작이다. 이 fallback을 그대로 둔 채 helper만 적용하지 말 것.
또한 현재 main은 실패한 번호를 NOT_FOUND로 처리할 수 있어, 엄격한 전체 실패/PARTIAL 및
범위 기반 결과 의미를 합의한 후 AnalysisRunner를 별도로 수정해야 한다. 실제 backend는 미수정이다.
