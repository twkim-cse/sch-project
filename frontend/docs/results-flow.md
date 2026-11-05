# 결과 화면·원문·삭제 오버레이 구현 기록

이후 개발용 미리보기를 전체 페이지와 업로드 흐름으로 확장했다. 현재 진입점·모드 분리·서버 호출 차단·전체 검증은 [개발용 미리보기 기록](./preview-mode.md)을 함께 확인한다.

## 구현 기준

- Figma 파일: https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9
- 실제 결과 화면 `98:197`, 원문 보기 `98:435`, 삭제 확인 `137:6`, 확인 완료 `158:8`의 디자인 컨텍스트와 스크린샷을 확인했다. 비어 있는 외부 프레임 `98:322`는 구현 기준으로 사용하지 않았다.
- React + Vite + JavaScript + CSS를 유지했다. 패키지를 추가하지 않았고, 공통 Header와 기존 이미지 에셋을 유지했다.

## 화면과 이동

| 동작 | 결과 |
| --- | --- |
| Frame 27 결과 확인하기 | 기존 `/results` 이동 유지 |
| 결과 필터 | 위험 조항은 HIGH, 전체 조항은 전체 카드, 점검 항목은 6개 요약을 표시. 별도의 중요도 선택도 유지 |
| 원문 보기 | 현재 결과 페이지 위에 Frame 49 표시. 선택 카드의 documentId와 clauseId 사용 |
| Frame 49 닫기 / Escape | 오버레이만 종료. 결과·필터·페이지 스크롤 유지 |
| 분석결과 삭제하기 | Frame 53만 표시. 이 시점에는 API를 호출하지 않음 |
| Frame 53 닫기 / Escape | 삭제 없이 복귀 |
| Frame 53 삭제하기 | DELETE 성공을 기다린 후 해당 문서/분석 참조와 완료 상태를 초기화하고 `/upload` 이동 |
| 확인 완료 | 데이터를 삭제하거나 참조를 초기화하지 않고 React Router로 즉시 `/upload` 이동 |
| 이후 Header 결과 확인 | 기존 서비스가 서버 상태·문서 보관 기한을 다시 확인한 후 `/results` 이동 |

결과 화면의 통계는 전체 카드 수, HIGH 카드 수, 선택한 특약 수로 계산하며 필터 전환에 따라 원본 데이터가 변하지 않는다. QUOTE_ONLY의 title/explanation은 출력하지 않는다. 찾지 못한 항목에는 `찾지 못했습니다. 직접 확인하세요`를 출력한다.

Frame 49는 기존 1183×839 모달 크기와 내부 스크롤을 유지한다. PDF 공백 차이를 무시해 인용 위치를 찾되, 렌더링하는 원문의 공백·개행·내용은 그대로 보존한다. 응답 clauseId가 요청과 다르면 원문을 표시하지 않고 재시도를 제공한다.

Frame 53은 Figma의 1295×502 크기, 흰 배경, 20px 모서리, 회색 설명, 주황색 삭제 버튼을 적용했다. 두 오버레이 모두 기존 AnalysisModal의 중앙 정렬 및 45% 검정 backdrop을 재사용한다. 작은 화면에서는 모달 크기를 뷰포트에 맞추고 내용은 내부에서 스크롤한다.

확인 완료는 하단 삭제 버튼의 맞은편에 위치하며 Noto Sans KR, 28px, 700, #0088FF다. 데이터 양에 따라 페이지 높이가 늘어나면 하단 영역도 문서 흐름에 맞춰 내려간다.

## API와 상태

기존 Spring API 클라이언트와 상태 재검증 서비스를 재사용한다.

- `GET /api/analyses/{analysisId}/result`
- `GET /api/documents/{documentId}/clauses/{clauseId}`
- 새로 추가한 `DELETE /api/documents/{documentId}`

DELETE는 204 No Content만 성공으로 받아들인다. 200 빈 응답, 200 JSON, Vite의 HTML 응답은 삭제 성공으로 처리하지 않는다. 실패 이유를 팝업에 표시하고 결과와 저장된 참조를 유지한다. 요청 중 삭제/닫기 버튼과 Escape를 막고 ref로 중복 전송을 방지한다. 요청 자체가 시작된 뒤의 화면 이동은 서버 작업을 취소했다고 간주하지 않는다. 뒤늦은 삭제 응답이 더 새 문서의 분석 참조를 지우지 않도록 문서/분석 식별자를 비교한다.

현재 실제 Spring 백엔드 연결은 검증되지 않았다. 2026-11-05 실행한 개발 서버의 `/api/analyses/connection-check/result`는 200 **text/html(Vite HTML)** 을 반환했다. API 경로를 구현한 것과 실제 서버에 연결된 것은 구분해야 한다. 실문서 삭제 요청은 전송하지 않았다. 연결 설정은 기존 `.env.example`의 `VITE_API_BASE_URL` 또는 `VITE_API_PROXY_TARGET`을 사용한다.

## 개발용 미리보기

`npm run dev` 실행 후 `/results?preview=1`로 접근한다. 이번 확인에서 새로 실행한 서버 주소는 `http://127.0.0.1:5174/results?preview=1`이다. 5173 포트가 이미 사용 중이어서 Vite가 5174를 선택했다.

- 기본 `/results`에는 예시 데이터를 자동으로 넣지 않는다. 결과가 없거나 조회가 실패한 경우 개발 환경에서만 미리보기 링크를 제공한다.
- 현재 전역 미리보기에서는 서버 조회를 전혀 하지 않는다. `?preview=1`이면 실제 분석 참조와 무관하게 메모리의 가상 결과만 사용한다. 이는 후속 요구사항에 따라 이전의 서버 결과 우선 정책을 변경한 것이다.
- 일반 URL에서는 기존 서버 결과 조회와 재검증 방식을 유지한다.
- 예시는 별도 개발 모듈에서 동적으로 불러오며 실제 완료 분석 상태나 sessionStorage에 저장하지 않는다.
- 주계약 2개와 특약 1개 카드, HIGH/FULL 및 NORMAL/QUOTE_ONLY, 6개 점검 요약, 카드별 긴 가상 원문을 확인할 수 있다.
- 미리보기 삭제는 화면의 예시 상태만 초기화하고 `/upload`로 이동한다. DELETE를 호출하거나 기존 실제 문서 참조를 초기화하지 않는다. 서버 데이터가 삭제되었다는 성공 안내도 하지 않는다.
- 프로덕션 빌드에서는 미리보기 진입과 예시 모듈이 제외된다. 출력 JS에서 `preview-document`, `미리보기 종합건강보험`, `미리보기용 가상 약관` 및 테스트 fixture 문자열이 발견되지 않은 것을 확인했다. `npm run preview`는 프로덕션 빌드 확인용이므로 개발용 예시 모드를 제공하지 않는다.

## 수정 및 생성 파일

| 구분 | 경로 | 변경 내용 |
| --- | --- | --- |
| 수정 | `src/pages/ResultsPage.jsx` | 미리보기, 필터, 찾지 못한 문구, 하단 두 동작과 삭제 상태 연결 |
| 수정 | `src/pages/ResultsPage.css` | 하단 정렬·버튼·미리보기 및 원문 메타데이터 스타일 |
| 수정 | `src/features/results/ClauseModal.jsx` | 중요도 표시, 조항 응답 검증, 검증되지 않은 해설 차단 |
| 수정 | `src/features/results/resultModel.js` | 원문을 변형하지 않는 인용 위치 매칭 |
| 생성 | `src/features/results/DeleteAnalysisModal.jsx` | Frame 53, 삭제 확정·대기·실패·중복 방지 |
| 생성 | `src/features/results/DeleteAnalysisModal.css` | Frame 53 전용 반응형 CSS |
| 생성 | `src/features/results/resultsPreview.js` | 명시적 개발용 가상 결과와 카드별 원문 |
| 수정 | `src/components/AnalysisModal.jsx` | 선택적 closeDisabled 지원. 기존 업로드 모달의 기본 동작 유지 |
| 수정 | `src/api/springApi.js` | DELETE 엔드포인트, 204 응답 처리 |
| 수정 | `src/services/analysisService.js` | 삭제된 분석 참조의 식별자 기반 제거 |
| 수정 | `src/features/analysis/AnalysisSessionProvider.jsx` | 삭제 후 해당 참조와 완료 결과 메모리 상태 초기화 |
| 생성 | `src/features/results/ResultsFlow.test.jsx` | 결과·원문·삭제·확인 완료·미리보기 통합 테스트 |
| 생성 | `src/features/results/resultModel.test.js` | 원문 보존 및 선택적 참조 제거 테스트 |
| 수정 | `src/api/springApi.test.js` | DELETE 성공·비정상 응답·실패 이유 검증 |
| 수정 | `src/features/upload/UploadFlow.test.jsx` | 원문 강조 부분도 서버 공백 그대로 보존하도록 검증 기대값 수정 |
| 생성 | `docs/results-flow.md` | 구현·파일·검증·실제 연결 상태 기록 |

새 UI 컴포넌트는 `DeleteAnalysisModal` 하나다. 기존 ResultCard, ClauseModal, AnalysisModal, Header를 재사용했다. 라우트·이미지·Frame 17~27 업로드 구현을 다시 작성하지 않았다.

## 검증 결과와 한계

- `npm.cmd test`: 8개 파일, 144개 테스트 통과. 기존 업로드·재시도·폴링·결과 확인·Header·뒤로 가기 회귀 테스트 포함.
- 새 검증: 실제 형식 결과 렌더링/집계, 모든 필터, 6개 점검 항목, QUOTE_ONLY 해설 차단, 카드별 원문/일치 강조/공백 보존, 조항 조회 실패/재시도, 닫기 후 상태 유지.
- 새 검증: 삭제 팝업 열기/취소 시 무요청, 204 도착 전 대기, 중복 전송 방지, 삭제 실패 후 결과 유지/재시도, 삭제 성공 후 상태 초기화와 `/upload` 이동.
- 새 검증: 확인 완료 시 무삭제·참조 유지 및 Header를 통한 재접근, 명시적 미리보기·실제 결과 우선·미리보기의 무서버 삭제.
- `npm.cmd run lint`: 통과.
- `npm.cmd run build`: 통과.
- `npm.cmd run dev -- --host 127.0.0.1`: 정상 실행. `/results?preview=1`과 `/upload`의 HTTP 200 응답 확인. HTTP 200은 브라우저 화면 검수와 구분했다.
- 실제 Spring 서버를 대상으로 한 결과/원문 조회 및 실문서 삭제는 미검증이다.
- 브라우저 검수 도구의 `cua.listBrowsers()`가 `The browser access host is unavailable`을 반환했다. 따라서 요청한 **1920×1080 실제 렌더링, 하단까지 직접 스크롤한 확인 완료 버튼, 실제 브라우저에서의 긴 모달 스크롤**은 검수하지 못했다. jsdom 테스트는 시각·배치·네이티브 포커스 동작을 입증하지 않으므로 이 항목을 통과로 기록하지 않았다.

현재 자동 검사에서 확인된 오류는 없다. 남은 검증은 실제 백엔드와 브라우저 연결이 필요한 위 항목들이다.
