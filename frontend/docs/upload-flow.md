# 보험약관 업로드 → 분석 결과 구현

## 현재 상태

Frame 17, 24, 25, 26, 27, 50, 45를 React + Vite + JavaScript + 일반 CSS로 구현했다. 이전의 `/upload`, `/results` 임시 화면을 실제 UI로 교체했다. 기존 시작 화면·홈·점검 항목·이용 가이드·Header·Frame 51 경로와 동작은 유지한다.

Figma의 해당 7개 노드를 조회해 코드와 스크린샷을 확인했다. 기능명세서 PDF 9쪽도 읽었으며, PDF 파서는 운영 앱 의존성에 추가하지 않고 임시 도구로만 사용했다.

**실제 Spring 서버 연동 성공을 확인한 상태는 아니다.** 서버 주소가 제공되지 않았고 현재 개발 서버의 `/api/documents/{임의 UUID}`는 JSON API 대신 HTML을 반환한다. 운영 코드에는 가짜 성공 응답이나 자동 Mock 모드가 없다. 해당 상황은 Frame 50에서 연결 대기/오류로 안내한다.

## API 설정과 실행

기본 API URL은 같은 출처의 `/api`이다. 개발 환경에서 Spring으로 연결하는 방법은 두 가지이며 한 가지를 선택한다. 설정 후 Vite를 재시작한다.

권장: `.env.local`에 프록시 대상 **실제 Spring 주소**를 설정한다.

```dotenv
VITE_API_BASE_URL=
VITE_API_PROXY_TARGET=http://localhost:8080
```

위 주소는 설정 형식의 예시이며 현재 서버가 해당 주소로 실행 중이라고 가정하지 않는다. 실제 주소로 바꿔야 한다. 프록시는 개발 환경 전용이다. 운영에서는 `/api`를 Spring으로 연결하거나 다음처럼 직접 API 출처를 지정한다.

```dotenv
VITE_API_BASE_URL=https://your-spring-host.example
```

직접 주소를 쓰는 경우 Spring에서 프런트엔드 출처에 대한 CORS 설정이 필요하다. base URL에는 `/api`를 덧붙이지 않는다. `.env.example`에는 비밀 값 없이 설정 형식만 기록했다.

```powershell
npm.cmd run dev
npm.cmd test
npm.cmd run lint
npm.cmd run build
```

## HTTP 요청 구현

| 시점 | 요청 | 처리 |
| --- | --- | --- |
| 업로드 시작 | `POST /api/documents` | FormData의 `file`에 선택 PDF를 담음. Content-Type boundary는 브라우저가 생성 |
| 문서 확인 | `GET /api/documents/{documentId}` | CHECKING 유지 / READY 확인 화면 / REJECTED 오류 화면 |
| 특약 확인 후 분석 시작 | `POST /api/analyses` | `{documentId, riderIds, viewAll}`. 전체 보기는 riderIds를 빈 배열로 전달 |
| 분석 상태 | `GET /api/analyses/{analysisId}` | QUEUED / RUNNING / DONE / FAILED와 실제 stepIndex·stepCount 반영 |
| 분석 DONE | `GET /api/analyses/{analysisId}/result` | 실제 결과 형식과 analysisId를 확인한 다음 결과 버튼 활성화 |
| 상단 결과 확인 / 결과 재방문 | 상태·문서·결과 GET | DONE뿐 아니라 문서의 현재 보관/삭제 여부도 재확인 |
| 원문 보기 | `GET /api/documents/{documentId}/clauses/{clauseId}` | 실제 조항 전문과 카드 인용을 공백 정리 규칙으로 대조·강조 |

프런트엔드는 위 Spring API만 호출하고 `/ai` 서버는 호출하지 않는다. 로그인·토큰·비밀키를 추가하지 않았다. HTTP 오류의 `{code,message}`, 문서의 `reject`, 분석의 `error`를 구분한다. HTML 응답·잘못된 JSON·네트워크 단절·시간 초과도 성공으로 처리하지 않는다.

## 상태 흐름

`/upload`의 기본 Frame 17은 계속 마운트되어 있고, 동일한 AnalysisModal의 내용만 바뀐다.

```text
파일 선택 → 업로드 시작 → Frame 24 (CHECKING)
                          ├─ READY → Frame 25 → 확인 → Frame 26
                          │                           └─ 분석 생성 성공 → Frame 27
                          │                                              ├─ QUEUED/RUNNING: 상태 조회
                          │                                              ├─ FAILED: 서버 오류 안내, 조회 중단
                          │                                              └─ DONE + 실제 결과 조회 성공: 결과 확인 버튼 활성화
                          │                                                                           └─ /results (Frame 45)
                          └─ REJECTED/HTTP 오류 → Frame 50
```

문서와 분석의 상태 조회 간격은 각각 2500ms다. 이전 요청이 끝난 뒤 다음 조회를 예약하므로 느린 서버에서 조회가 겹치지 않는다. 타이머는 조회 간격에만 사용하며, 시간이 지났다는 이유로 상태나 진행률을 바꾸지 않는다.

분석 진행 단계는 문서 텍스트 추출 완료와 EXTRACTING / CHECKING_QUOTES / EXPLAINING / FINALIZING에 대응한다. 완료는 초록색, 진행 중은 파란색, 대기는 회색 배지로 구분한다. 예상 퍼센트는 stepIndex/stepCount이며 DONE 전에는 최대 99%, DONE이면 100%다. 결과 조회 실패 시 DONE이어도 결과 버튼은 비활성화하고 결과 재조회만 제공한다. 진행 조회 실패도 재분석 POST 대신 상태 GET을 다시 시도한다.

## 파일 선택과 오류

- 네이티브 `<input type="file">`를 ‘파일 업로드 하기’ 버튼에 연결했다. `multiple`을 사용하지 않는다.
- 드롭한 파일도 같은 검사 함수를 사용한다. 개수·확장자·MIME·빈 파일·용량을 확인한다. 실제 PDF 내용 검증과 문서 종류/구조 판정은 서버가 맡는다.
- 50MB 임시 기준은 `src/config/analysis.js`의 `MAX_PDF_SIZE_MB`이며 현재 바이트 계산은 `50 × 1024 × 1024`다.
- 이름·크기·형식을 표시하고 삭제·다시 선택할 수 있다. 선택 파일이 없으면 업로드 시작 버튼을 비활성화한다. 취소 버튼은 없다.
- NOT_PDF / FILE_TOO_LARGE / NO_TEXT / NOT_TERMS / POLICY_DETECTED / STRUCTURE_FAILED를 Frame 50에서 서로 다른 다음 행동으로 안내한다.
- POLICY_DETECTED만 보고 파일 삭제를 단정하지 않는다. 서버의 `deleted`, `fileDeleted`, `documentDeleted`가 명시적으로 true/false일 때만 해당 사실을 표시하고, 필드가 없으면 삭제 여부 미확인으로 안내한다. 실제 백엔드의 삭제 확인 필드가 다른 경우 어댑터를 맞춰야 한다.
- Frame 50의 닫기·다시 시도하기는 파일 선택 초기 단계로 돌아간다. alert()를 사용하지 않는다.

## 모달과 중단

AnalysisModal은 1183×839 기준, Frame 50은 1295×502 기준이며 작은 화면에서는 viewport 안으로 줄이고 내부 스크롤을 허용한다. 공통 배경은 `rgba(0,0,0,0.45)`, 흰색 패널, 20px 반경이다. 기존 Frame 51도 같은 모달 수명 관리 훅을 재사용한다.

각 단계에 닫기가 있으며 ESC도 같은 동작을 한다. 닫기/이동/언마운트 시 타이머와 AbortController를 정리하고, 늦게 도착한 이전 요청의 응답을 무시한다. 파일·선택·진행 UI를 초기화하고 `/upload`에 남는다. 서버 작업 취소 API는 호출하지 않으며, 시작된 분석이 계속될 수 있음을 안내한다. 서버 작업의 ID는 결과를 나중에 확인할 수 있도록 참조만 보관한다.

## 결과와 보관 기한

분석 상태 GET에는 expiresAt이 없으므로, 보관 기한을 만들어 넣지 않는다. 문서 GET의 expiresAt을 사용하고, 재조회 시 문서가 READY인지와 404/410 여부를 확인한다. 만료 시각이 명시적으로 과거면 결과를 사용하지 않는다. 서버가 만료 시각을 null로 반환하는 경우 ‘확인되지 않음’을 표시하며, 매 접근마다 실제 문서 및 결과 조회 성공으로 현재 사용 가능 여부를 확인한다.

결과는 상위 React Context의 메모리에 보관한다. sessionStorage에는 analysisId/documentId/expiresAt 참조만 저장하고 상품명·인용·해설·PDF 내용은 저장하지 않는다. localStorage에는 업로드 파일을 저장하지 않는다.

Frame 45는 실제 document, groups, selectedRiders, checkSummary를 렌더링한다. 주계약/특약 그룹과 점검 항목 순서, 중요도 필터, 6개 항목 요약, NOT_FOUND 안내, 원문 보기, 한계 고지를 제공한다. QUOTE_ONLY는 응답에 제목·해설이 잘못 들어 있어도 표시하지 않는다. 빈 객체, 다른 analysisId, 잘못된 카드/요약 형식을 정상 결과로 받아들이지 않는다. 결과 화면은 고정 높이로 자르지 않고 모든 카드가 문서 스크롤에 포함된다.

## 테스트 결과

자동 테스트는 실제 Spring 응답이 아닌 **테스트 전용 주입 응답**을 사용한다. 기본 HTTP 어댑터를 경유하는 통합 테스트도 별도로 포함한다.

| 검사 | 결과 |
| --- | --- |
| 파일 선택 버튼 → 네이티브 input 클릭 연결 | 통과. 실제 Windows 선택창 조작은 미검증 |
| PDF 1개·드롭·메타데이터·삭제·재선택·50MB 경계 | 통과 |
| 비PDF/초과/서버 거절·오류별 Frame 50 | 통과 |
| 업로드 중 중복 요청 방지·CHECKING 유지·READY 전환 | 통과 |
| Frame 25 실제 응답 필드·null 안내 | 통과 |
| 특약 여러 개·행 클릭·전체 보기 우선·주계약 고정 | 통과 |
| Frame 27 서버 단계·예상 진행률·동일 단계 유지·FAILED 중단 | 통과 |
| DONE지만 결과 미수신/오류/잘못된 형식일 때 이동 차단 | 통과 |
| 결과 재조회·상태 재조회·지연 응답 무시 | 통과 |
| 24→25→26→27 동일 dialog와 Frame 17 배경 유지 | 통과 |
| 모든 업로드 오버레이 닫기·타이머 중단·AbortSignal | 통과 |
| 실제 결과 그룹·QUOTE_ONLY 숨김·원문 강조 | 통과 |
| 기존 공통 Header·Frame 51·이력 이동 | 기존 회귀 테스트 통과 |
| 실제 Spring에서 PDF 처리·AI 완료·삭제/만료 응답 | 백엔드 주소/서버 준비 전 미검증 |
| 실제 브라우저 Figma 시각 비교·네이티브 inert·Windows 선택창 | 브라우저 제어 연결 불가로 미검증 |

최종 실행 결과:

- `npm.cmd test`: **6개 테스트 파일, 122개 통과**.
- `npm.cmd run lint`: 통과.
- `npm.cmd run build -- --logLevel warn`: 통과.
- 기존 PNG 7개 모두 작업 전후 SHA-256 해시 일치. 추가한 원본은 문서 스택 PNG와 원문 구분선 SVG뿐이다.
- 개발 서버의 기존 6개 경로 및 새 업로드/결과/흐름/API 모듈 HTTP 200 확인. API 경로의 HTML 응답은 API 연결 성공으로 집계하지 않았다.
- 운영 빌드에서 테스트 상품명·테스트 해설·Figma 예시 상품 문구가 포함되지 않음을 확인했다.

남은 검증은 실제 Spring 서버와 보험약관 PDF를 이용한 성공/거절/분석/보관 만료 시나리오, 실제 Windows 파일 선택창, 브라우저에서의 디자인·반응형·네이티브 모달 동작이다. 주소가 준비되면 `.env.local`을 설정하고 재시작하여 진행할 수 있다.

## 변경 파일

수정한 파일:

- `src/App.jsx`, `src/App.test.jsx`
- `src/pages/UploadPage.jsx`, `src/pages/ResultsPage.jsx`
- `src/services/analysisService.js`
- `src/features/analysis/AnalysisNavigationProvider.jsx`
- `src/components/NoAnalysisModal.jsx`
- `vite.config.js`
- `docs/frontend-implementation.md`, `docs/design-audit.md`

생성한 파일:

- `.env.example`
- `src/config/analysis.js`
- `src/api/springApi.js`, `src/api/springApi.test.js`
- `src/services/springAnalysisBackend.js`, `src/services/springAnalysisBackend.test.js`
- `src/features/analysis/AnalysisSessionContext.js`, `AnalysisSessionProvider.jsx`, `useAnalysisSession.js`
- `src/components/AnalysisModal.jsx`, `AnalysisModal.css`, `useModalDialog.js`
- `src/features/upload/useUploadFlow.js`, `uploadModel.js`, `uploadModel.test.js`
- `src/features/upload/DocumentChecking.jsx`, `DocumentConfirmation.jsx`, `RiderSelection.jsx`, `AnalysisProgress.jsx`, `UploadErrorModal.jsx`
- `src/features/upload/UploadFlow.css`, `UploadFlow.test.jsx`
- `src/features/results/ResultCard.jsx`, `ClauseModal.jsx`, `resultModel.js`
- `src/pages/UploadPage.css`, `ResultsPage.css`
- `src/test/analysisFixtures.js` (테스트 파일에서만 사용)
- `src/assets/insurance-documents.png`, `clause-divider.svg` (Figma 원본)
- `docs/upload-flow.md`

운영 앱 의존성 package.json/package-lock.json은 이번 작업에서 변경하지 않았다. 기존 이미지도 삭제·교체하지 않았다.
