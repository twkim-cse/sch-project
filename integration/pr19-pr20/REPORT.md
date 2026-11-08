# PR19 backend + PR20 AI HTTP 통합 검증 (로컬 제안)

기준:main `34bbc73946dba8a3daf8409162fa8268ff23fe16`(PR19 병합),AI PR20 `32cea4337db649b4d52fda5f27fa47367ceee5eb`.
작업트리 `.worktrees/pr19-pr20-integration`,브랜치 `test/pr19-pr20-integration`. 원래PR18/PR20/다른작업트리는보존했다. 검증 당시 main병합/push/PR변경/배포/실제Gemini호출은없었다. 이후 사용자 요청으로 이 검토용 브랜치만 공유하며, 수정안의 실제 적용·병합은 백엔드 담당자가 결정한다.

## Claude Code 검토용 브랜치 사용

기존 작업을 보존하려면 저장소 안에서 별도 worktree를 만든다:

```bash
git fetch origin test/pr19-pr20-integration
git worktree add ../sch-pr19-pr20-review -b review/pr19-pr20-integration origin/test/pr19-pr20-integration
```

Claude Code를 새 디렉터리에서 열어 이 REPORT.md와 원본PR20 커밋 대비 diff를 검토한다:

```bash
git diff 32cea4337db649b4d52fda5f27fa47367ceee5eb..HEAD -- backend ai/tools/pr19_http_mock.py integration/pr19-pr20
```

**이 브랜치에는 제안 코드가 이미 적용돼 있다. BACKEND_PROPOSAL.patch를 다시 적용하지 않는다.** 패치는 원본PR20 기반의 다른 검토 작업트리에 선택적으로 적용할 때만 사용한다. 브랜치는 PR20의 AI 변경도 상속하므로 main 대비 diff와 이번 백엔드 제안 diff를 구분한다.

실제PDF/결과/로그는 공유하지 않는다. 실제PDF 통합 테스트를 재현하려면 해당 파일을 각자 로컬에 준비하고 아래 환경변수로 지정해야 한다. Gemini는 Mock만 사용하며 운영 배포용 서버로 이 하네스를 실행하지 않는다. main병합/새PR/기존PR수정은 이번 공유 작업에 포함하지 않는다.

## 원래 조합의 결함 재현

실제New Dental PDF256쪽을 Spring `POST /api/documents` multipart로 업로드했다. READY/256쪽/1,472개 조항을 확인하고 이 실제 파싱 결과로 AnalysisService를 실행했다. 미리 JSON을 주입해 PDF 파싱을 건너뛴 테스트가 아니다.

1. 후보plan은HTTP200이고1,186개 후보/66parts/응답1,618,243bytes였다. 원래Spring WebClient 기본256KiB aggregation한도로 decode실패,분석FAILED/result409,extract호출0회였다. 별도직접기본WebClient호출에서도 `DataBufferLimitException`을재현했다. 이는413이아닌Spring수신버퍼문제다.
2. 합성56개/3parts에서중간part에503/502/413/422/429/504를각각주입했다. 원래AiClient는동일실패HTTP를즉시2번보낸후실패했고,AnalysisRunner는빈성공결과로대체해뒤part와merge를진행했다. **6종모두DONE/result200/NOT_FOUND가나왔다.** 실패part빈결과제출1회,실패이후요청계속실행을확인했다. 이는정상통합성공이아니라결함재현이다.
3. 기존Java테스트에도부분실패를DONE/NOT_FOUND로기대하는검증이있었다. 이임시브랜치에서만실패보존계약의회귀테스트로바꿨다.
4. 계획수신버퍼만해결한중간실험에서는66개explain을한요청으로보내AI의요청당32생성시도예산에걸렸다. Spring은실패해설을원문전용으로대체해DONE이었다. 이를해설/독립검토성공으로보고하지않았다.

## 임시 백엔드 변경안 (PR20에는 포함하지 않음)

- AiClient:AI HTTP응답aggregation상한40MiB로명시. AI원본16MB/500part안전한도는해제하지않음. 즉시동일HTTP자동재시도를제거하고FastAPI의내부제한복구와중첩하지않음.
- AnalysisRunner:필수part실패시즉시중단,성공part만CandidateCheckpoint에보존,merge/해설금지,기존분석FAILED/result409. 새상태enum을강제로프론트에추가하지않음.
- CandidateMergeResponse/SelectionDto:coverage/selection_id/plan_id/policy_version을받고분석체크포인트에보관.
- AnalysisStore/CandidateCheckpoint:성공part응답·실패part·미완료IDs·원본/계획/coverage를**인메모리**보관. 삭제시연결자료도삭제. 운영DB/재시작복구/자동resume는구현하지않음.
- AnalysisController:로컬제안 `/api/analyses/{id}/candidate-progress`에비민감진행요약과coverage를노출. `resumable=false,durable=false`를명시하고원문/응답본문은노출하지않음.
- explain:2개item씩순차HTTP로나눠AI요청당호출예산안에서생성·독립검토. 기존AI모듈·의미검토·reviewfail정책은변경하지않음.

이정책은팀원승인전로컬제안이다. 운영에필요한durable체크포인트·cooldown보존/수동재개·인증·동시작업상한·전체요청예산은백엔드팀의별도설계가필요하다. 새분석시작으로성공part를무조건재호출하는재개대용동작을권장하지않는다.

## 개선안의 New Dental 전체 후보 Mock 결과

| 항목 | 확인 |
|---|---|
| 실제PDF업로드·파싱 |202→READY,256쪽,1,472조항|
| 후보/미선정 |1,186 /286 (전체의약80.57%후보)|
| 후보규칙 |recall-rules-2|
| 후보요청 |66개,모두HTTP200|
| 입력예산 |최대25조항/23,857bytes,초과0|
| mode/focus/selection_id/plan_id |원래계획과일치|
| 후보merge |1회HTTP200,중복제거0|
| source/metadata/인용 |AI보존검증,Spring최종card ID/rider/page/section/article/paragraph/from_table/인용대조|
| 해설·독립검토 Mock |66초안+66검토,33 HTTP요청,전부200/pass|
| Spring저장/결과조회 |DONE/200,66개합성Mock카드|
| 실제Gemini |0회|

기존77parts는**전체무손실**계획이다. 이번66parts는**후보**계획이며286개미선정조항을모델이분석했다고주장하지않는다. 미선정사유는 no_rule_evidence_unreviewed247,법규34,nonprose5다. 후보재현율/위험조항실제탐지정확도는미검증이다.

coverage는 full_document_analyzed=false,recall_verified=false,risk_absence_determined=false이다. 개선안은coverage를보관/별도조회하지만기존최종결과checkSummary의NOT_FOUND라벨과프론트는변경하지않았다. **미검출을전체약관의위험없음으로보이지않도록결과/프론트표시협의가여전히필요하다.**

## 오류 주입 비교

| 실패 HTTP | 원래 PR19+20 | 로컬 개선안 |
|---|---|---|
|413/422/429/502/503/504 각각|동일part2회,실패를빈결과로merge,DONE/200,NOT_FOUND|실패part1회,뒤part중단,merge0/해설0,FAILED/result409,성공part1개보관,PARTIAL|

Mock오류는Spring-FastAPI경계에주입했다. 실제Gemini서비스가503을일으킨테스트가아니다. AI내부503/429/504정책은별도Python312개회귀로확인한다. 로컬개선안은같은HTTP를즉시반복하지않으며장애시자동resume가가능하다는주장을하지않는다.

## 재현 명령

AI폴더,로컬테스트전용:

```powershell
python -m unittest discover -p "test_*.py" -q
python tools/pr19_http_mock.py --port 18082
```

backend폴더,Java21설치환경:

```powershell
$env:PR1920_MOCK_URL='http://127.0.0.1:18082'
$env:PR1920_PDF='C:/local-only/New_Dental_provision2604.pdf'
$env:PR1920_REPORT_DIR='C:/local-only/pr19_pr20_report'
$env:PR1920_EXPECT_SAFE_FAILURE='true'
./gradlew.bat test bootJar
```

PDF는저장소밖실제파일을사용한다. env가없으면새실제PDF통합테스트는skip한다. baseline=false모드는**원래PR20코드에테스트하네스만추가한상태**의결함재현용이며개선코드위에서실행하지않는다. 실제Spring실행/파싱과MockGemini를명확히구별한다.

로컬은JDK23+release21로빌드/실행했다. Java21런타임/DockerCLI없어이미지build/run은미검증이다. React브라우저E2E/실제Gemini전체분석도미검증이다. 런타임원문/JSON/테스트로그는Git제외local_policy에만저장했다.

## 병합 판단

최종Python전체312개통과. 임시백엔드개선안의Java전체66개중65개통과/1개기존PDF진단skip/실패0,bootJar성공. 새HTTP통합테스트3개는실제PDF정상흐름,6종part오류시나리오,기본WebClient버퍼오류를검증한다. 일부실패DONE을정상으로기대하던기존테스트는실패보존회귀로변경했다. baseline결함재현과개선후성공을구분한다.

로컬검토용 [BACKEND_PROPOSAL.patch](BACKEND_PROPOSAL.patch)는PR20원본에 `git apply --check`가통과했다. 적용/게시/승인은아직하지않았다. AI하네스는운영서버가아니며별도로 `ai/tools/pr19_http_mock.py`를사용한다.

Git충돌은없고AI와Spring후보API JSON계약은맞는다. 하지만**현재PR19와PR20그대로의운영통합은준비완료로판정하지않는다**. 수신버퍼/실패빈결과/coverage/해설묶음문제를백엔드팀과해결한후동반검증을권장한다. PR20AI코드에백엔드변경을섞거나PR18을병합하는방법은사용하지않았다. 이번변경안도원격에올리지않았다.
