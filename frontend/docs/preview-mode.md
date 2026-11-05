# 개발 전용 미리보기

## 접속과 종료

`npm run dev`로 실행한 Vite 개발 서버에서 다음 주소를 사용한다.

- 시작 화면: http://localhost:5173/?preview=1
- PDF 업로드: http://localhost:5173/upload?preview=1
- 결과 화면: http://localhost:5173/results?preview=1

이번 점검에서 localhost:5173의 `/src/App.jsx`가 HTTP 200으로 최신 PreviewModeProvider 구현을 제공하는 것을 확인했다. 이미 실행 중인 서버를 종료하거나 교체하지 않았다. 다른 포트로 개발 서버를 실행했다면 주소의 포트만 바꾼다.

활성화 조건은 `import.meta.env.DEV`이면서 URL의 `preview` 값이 정확히 `1`인 경우다. 내부 Header·CTA·결과 이동에는 `preview=1`을 유지한다. 별도 localStorage/sessionStorage 활성화 플래그를 남기지 않으므로 `/` 또는 `/upload` 등 일반 URL로 새로 접속하면 실제 서비스 모드다. 개발자 메뉴의 `미리보기 종료 · 일반 모드` 링크로도 종료할 수 있다.

배포 빌드와 `npm run preview`에서는 `?preview=1`로 개발 모드를 활성화할 수 없다. `npm run preview`라는 Vite 명령은 개발용 샘플 모드와 다른, 배포 결과물 확인 명령이다.

## 개발자 메뉴

화면 오른쪽 아래의 `개발자 미리보기` 버튼을 펼치면 아래 14개 화면을 선택할 수 있다. 기본 상태는 접힘이며, 기존 페이지 레이아웃을 재배치하지 않는 작은 메뉴다. 열린 메뉴만 제한된 높이 안에서 스크롤된다.

| 메뉴 | 배경 경로 | 표시 내용 |
| --- | --- | --- |
| Frame 46 - 시작 화면 | `/` | 기존 시작 화면 |
| Desktop-3 - 홈 화면 | `/home` | 기존 홈 화면 |
| Desktop-5 - 점검 항목 | `/check-items` | 기존 점검 항목 |
| Frame 52 - 이용 가이드 | `/guide` | 기존 가이드 |
| Frame 17 - PDF 업로드 | `/upload` | 파일 선택과 샘플 테스트 버튼 |
| Frame 24 - 문서 확인 중 | `/upload` | 문서 확인 중 오버레이 |
| Frame 25 - 문서 확인 완료 | `/upload` | 샘플 문서 정보·특약 목록 |
| Frame 26 - 가입한 특약 선택 | `/upload` | 특약 선택 오버레이 |
| Frame 27 - AI 분석 진행 | `/upload` | 수동 진행 조절 오버레이 |
| Frame 45 - 분석 결과 | `/results` | 샘플 결과 |
| Frame 49 - 원문 보기 | `/results` | 선택 카드의 가상 원문 |
| Frame 50 - 파일 오류 | `/upload` | 4가지 오류 선택 |
| Frame 51 - 분석 결과 없음 | `/home` | 결과 없음 팝업 |
| Frame 53 - 분석 데이터 삭제 확인 | `/results` | 삭제 확인 팝업 |

직접 진입 시 `previewFrame=24` 같은 보조 쿼리를 사용한다. 오버레이만 단독 페이지로 만들지 않고 기존 업로드·홈·결과 배경을 유지한다. native dialog가 열린 동안에는 바깥 메뉴를 숨기고 오버레이 하단에 개발자 메뉴를 제공하므로, 모달의 포커스 제한을 우회하지 않고 다른 프레임으로 이동할 수도 있다.

Frame 45/49/53을 메뉴로 직접 열면 샘플을 다시 준비한다. 샘플 삭제 뒤에도 메뉴에서 반복 테스트할 수 있다. Frame 51 직접 보기는 현재 샘플을 실제로 삭제하지 않고 빈 결과 팝업만 보여준다.

## 순차 테스트

1. `/upload?preview=1`에서 `샘플 약관으로 테스트`를 누른다. 파일을 선택하지 않아도 Frame 24가 열린다.
2. Frame 24의 개발용 `문서 확인 완료`를 누르면 Frame 25가 열린다. 서버 응답이나 타이머를 기다리지 않는다.
3. `(예시) 종합건강보험`, `2025년 4월`, `212쪽` 및 암진단·질병입원·수술비·상해보장 특별약관 4개를 확인한다.
4. `확인하고 다음으로`를 누르면 Frame 26에서 특약을 선택할 수 있다. 주계약은 항상 포함한다. `전체 보기`를 켜면 특약 개별 선택을 잠그며, 해제하면 앞서 개별 선택한 상태가 유지된다.
5. `분석 시작하기`로 Frame 27을 연다. 5개 단계 버튼, `다음 단계`, `모든 단계 완료`로 진행 상태를 조절한다. 처음에는 텍스트 추출이 진행 중이고 나머지는 대기 중이다. 완료한 단계 수에 따라 0/20/40/60/80/100%와 완료·진행 중·대기 상태가 바뀐다. 이전 단계로 돌아갈 수도 있다.
6. 모든 단계가 완료되면 `결과 확인하기`가 활성화된다. 선택 특약과 전체 보기 설정을 반영한 Frame 45로 이동한다.

실제 로컬 PDF를 선택하거나 드래그한 뒤 `업로드 시작하기`를 눌러도 같은 가상 흐름으로 이동한다. PDF 확장자·크기·빈 파일 등 기존 클라이언트 검증은 재사용하지만 파일 내용은 서버에 전송하지 않는다. 샘플 메타데이터가 선택한 실제 PDF의 분석 결과라는 의미는 아니며, 화면에 가상 데이터와 무전송 안내를 표시한다.

6개 점검 항목은 면책 사유, 대기 기간, 갱신 조건, 알릴 의무, 약관상 질병·수술의 정의, 중도 해지 및 해지환급금이다. 일부는 카드가 있고 나머지는 찾지 못한 상태다. 실제 카드·선택 특약 수로 통계를 계산하며, 선택 범위에 따라 가상 결과도 달라진다.

Frame 50에서는 PDF 형식 오류, 50MB 용량 초과, 텍스트 추출 실패, 보험약관이 아닌 문서를 선택하고 대응 문구를 확인할 수 있다. 기존 UploadErrorModal을 재사용한다.

## 결과·삭제·실제 데이터 보호

- 기존 ResultCard, ClauseModal, DeleteAnalysisModal, NoAnalysisModal 및 공통 AnalysisModal을 재사용한다.
- 원문 보기에는 카드별 clauseId에 해당하는 긴 가상 원문을 표시한다. QUOTE_ONLY에는 AI 해설을 만들지 않는다.
- `확인 완료`는 `/upload?preview=1`로 즉시 이동하며 샘플 결과를 삭제하지 않는다. Header의 결과 확인으로 다시 접근할 수 있다.
- Frame 53에서 닫으면 결과가 유지된다. 삭제를 확정하면 메모리의 샘플만 초기화하고 업로드로 이동한다. 서버 DELETE는 호출하지 않는다.
- 기존 저장된 실제 analysisId/documentId는 미리보기에서 읽어 샘플로 사용하거나 덮어쓰거나 삭제하지 않는다. 샘플·진행 상태는 새로고침하면 초기화된다.
- 일반 서비스 모드는 기존 Spring API, 상태 재검증·만료 확인·업로드·폴링을 계속 사용한다. API 구현을 제거하지 않았다.
- 기존 결과 전용 미리보기의 서버 우선/조회 실패 후 샘플 정책을 확장했다. 현재 전역 미리보기에서는 **실제 서버 호출이 전혀 없고**, 일반 URL에서만 실제 서비스를 사용한다.

## 구조와 변경 파일

새 패키지나 UI 라이브러리를 추가하지 않았다. 기존 이미지, 일반 CSS, React Router를 유지했다.

새 파일:

| 파일 | 역할 |
| --- | --- |
| `src/features/preview/PreviewModeProvider.jsx` | DEV 전용 지연 로드, 메모리 샘플 세션 주입, 직접 Frame 51 보기 |
| `src/features/preview/PreviewModeContext.js` | 일반 모드 기본값과 미리보기 컨텍스트 |
| `src/features/preview/usePreviewMode.js` | 모드 접근 |
| `src/features/preview/previewRouting.js` | 일반 URL을 변경하지 않고 미리보기 URL에만 쿼리 유지 |
| `src/features/preview/mockAnalysisSession.js` | 실제 네트워크 구현이 없는 샘플 API·서비스·메모리 저장소 |
| `src/features/preview/MockAnalysisFlow.jsx` | 파일 선택 검증, 수동 문서 확인·특약·5단계 진행 상태 |
| `src/features/preview/PreviewNavigation.jsx` | 접고 펼치는 14개 화면 이동 메뉴 |
| `src/features/preview/PreviewMode.css` | 개발자 도구에만 적용하는 CSS |
| `src/features/preview/PreviewMode.test.jsx` | 진입·분리·메뉴·순차 진행·서버 무호출 검증 |
| `docs/preview-mode.md` | 사용법·변경 파일·검증 기록 |

수정 파일:

| 파일 | 변경 |
| --- | --- |
| `src/App.jsx` | DEV와 URL 조건으로 미리보기 Provider를 지연 로드. 일반 Application 유지 |
| `src/features/analysis/AnalysisSessionProvider.jsx` | 기본 true인 persistReferences 옵션. 미리보기에서만 저장소 읽기·쓰기·삭제 차단 |
| `src/features/analysis/AnalysisNavigationProvider.jsx` | 결과 이동 시 미리보기 쿼리 유지 |
| `src/components/Header.jsx` | 로고·홈·점검·가이드 이동 시 모드 유지 |
| `src/components/ActionLink.jsx` | 기존 CTA 이동 시 모드 유지 |
| `src/components/AnalysisModal.jsx` | 미리보기에서만 모달 내부 개발 메뉴 추가 |
| `src/components/NoAnalysisModal.jsx` | 미리보기에서만 모달 내부 개발 메뉴 추가 |
| `src/pages/UploadPage.jsx` | 기존 UploadScreen을 공유하고 실제/가상 흐름을 분리. 미리보기 전용 샘플 버튼 |
| `src/features/upload/AnalysisProgress.jsx` | 가상 흐름에서만 수동 5단계 상태 주입. 기본 실제 진행 계산 유지 |
| `src/features/results/resultsPreview.js` | 기존 샘플 모듈을 확장하여 요구한 상품 정보·4개 특약·선택 범위·6개 점검 항목 제공 |
| `src/pages/ResultsPage.jsx` | 결과 전용 fallback 대신 주입된 세션 사용. 직접 Frame 49/53과 모드 유지 이동 지원 |
| `src/features/results/ResultsFlow.test.jsx` | 전역 미리보기의 실제 서버 무호출 정책을 검증하도록 기존 테스트 확장 |
| `docs/results-flow.md` | 후속 전역 미리보기 정책과 문서 링크 반영 |

일반 서비스 화면의 CSS와 에셋은 변경하지 않았다. 공통 컴포넌트에는 모드 전달·선택적 개발 도구 표시를 추가했다. 업로드 실제 API/polling hook과 Spring API 클라이언트는 수정하지 않았다.

## 검증 결과

- `npm.cmd test`: **9개 파일, 171개 테스트 통과**.
- 새 미리보기 테스트 27개: 활성화·종료·프로덕션 차단·모든 14개 메뉴 진입·올바른 오버레이 배경·닫기·모달 내부 메뉴·쿼리/뒤로 가기 유지·순차 진행·개별/전체 특약·수동 진행률·로컬 PDF 선택·4개 오류.
- 기존 결과 테스트: 필터, 원문, 닫기, 삭제 취소/성공/실패/중복 방지, 확인 완료와 재접근 유지.
- 실제 서비스 회귀: 기존 Frame 17~27 API 업로드·상태 확인·폴링·결과 조회·오류 재시도·Header 및 역사 탐색 테스트 통과.
- 미리보기 테스트에서 실제 API 객체, 실제 서비스 객체 및 fetch를 감시했다. 테스트한 모든 가상 페이지·오버레이·업로드·원문·삭제 흐름에서 실제 백엔드 호출 **0회**.
- 실제 분석 참조가 저장되어 있어도 미리보기 샘플 생성·확인 완료·삭제가 해당 저장값을 변경하지 않는 것을 확인했다.
- `npm.cmd run lint`: 통과.
- `npm.cmd run build`: 통과.
- 빌드된 JS/CSS에서 샘플 문서 ID·상품명·가상 원문·개발자 메뉴·모의 세션 모듈이 제외된 것을 확인했다. DEV=false 테스트에서도 쿼리로 개발 메뉴나 가상 업로드 모달을 활성화할 수 없었다.
- localhost:5173과 127.0.0.1:5174가 최신 App 모듈을 제공하는 것을 HTTP로 확인했다. HTTP 확인과 DOM 자동 테스트는 실제 브라우저 시각 검수와 구분한다.

현재 실제 Spring 서버 연결 확인은 이번 작업의 테스트 전제가 아니다. 일반 서비스 기능의 실제 서버 통합 검증은 백엔드 연결 후 필요하다. 브라우저 검수 도구 연결이 불가능한 환경이므로 실제 브라우저의 레이아웃·native dialog 포커스·스크롤에 대한 직접 검수는 완료하지 못했다.
