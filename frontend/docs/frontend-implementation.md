# 들다 홈 화면 구현 기록

업로드·분석·결과 화면의 최신 상태와 API 연결 설정은 [업로드 흐름 구현 기록](./upload-flow.md)을 확인한다.

이 문서는 초기 구현 기록이다. 이후 원본 Figma 조회를 통한 시각 값 보정, 점검 항목·가이드 화면 반영, 이력 이동 오류 수정과 최신 검증 결과는 [디자인·기능 점검 보고서](./design-audit.md)를 기준으로 확인한다.

## 구현 범위

- 기존 Frame 46의 페이지, 사진, 로고, CSS를 유지했다.
- `/home`은 요청서의 Desktop - 3 / Desktop - 1 구조로 구성했다.
- 콘텐츠 너비는 최대 1440px이며 1920px 화면 중앙에 배치된다.
- 데스크톱 콘텐츠의 최소 높이는 3050px이다. 첫 영역 1010px, 소개 영역 세 개는 각각 680px이며, 고정 높이로 자르거나 내부 스크롤에 가두지 않는다.
- 좁은 화면에서는 상단 메뉴를 두 번째 줄로, 소개 영역을 한 열로 배치한다.
- 사용자 제공 로컬 PNG를 개별 이미지로 사용하고 텍스트와 버튼은 React로 구현했다.
- Figma 원본의 세부 좌표는 확인하지 못했으므로 원본과 픽셀 단위 일치 여부는 미검증이다. Frame 51은 요청에 따라 기능을 우선 구현한 기본 디자인이다.

## 로컬 이미지 연결

| 영역 | 기존 파일명 |
| --- | --- |
| 메인 AI 이미지 | `src/assets/화이트 타일 위 파란 뇌.png` |
| 분석 결과 소개 | `src/assets/AI 분석 보고서 검토.png` |
| 핵심 점검 항목 소개 | `src/assets/보험 체크리스트와 보안 방패 (1).png` |
| 이용 가이드 소개 | `src/assets/파란 도움 아이콘이 있는 열린 안내서.png` |
| 기존 시작 화면 | `src/assets/insurance-review.png` |
| 공통 로고 | `src/assets/deulda-logo.png` |

이미지 파일은 수정하거나 삭제하지 않았다. `미니멀 파란 체크박스 아이콘.png`도 원본 그대로 보관했다. 현재 홈 소개 영역에 추가로 필요한 이미지는 없다.

## 경로와 버튼

| 기능 | 동작 |
| --- | --- |
| 들다 로고 | `/` |
| 시작 화면의 ‘홈으로’ | `/home` |
| 다른 화면의 ‘홈으로 가기’ | `/home` |
| 상단 ‘결과 확인’, 결과 소개의 ‘더 알아보기’ | 동일한 `openResults()` 호출 |
| 유효한 완료 결과 있음 | `/results`, 라우터 state에 `analysisId` 전달 |
| 유효한 완료 결과 없음 | 현재 화면 위에 Frame 51 모달 표시 |
| 상단 ‘점검 항목’, 해당 소개의 ‘더 알아보기’ | `/check-items` |
| 상단 ‘이용 가이드’, 해당 소개의 ‘더 알아보기’ | `/guide` |
| 메인·점검 항목·이용 가이드의 ‘약관 분석하기’ | `/upload` |

모든 경로는 `src/routes/paths.js`를 공유하고 React Router로 이동한다. `/upload`, `/results`, `/check-items`, `/guide`는 이후 실제 디자인으로 교체할 독립 페이지이다.

## 결과 검사와 Spring 연결 위치

`src/services/analysisService.js`의 `createAnalysisService()`가 연결 경계다. 현재 기본 서비스에는 백엔드가 연결되어 있지 않으며 네트워크 요청이나 가짜 결과 생성을 하지 않는다.

완료 결과는 다음 조건을 모두 만족해야 한다.

1. 현재 분석 참조의 `analysisId`가 유효하고 메타데이터의 ID와 일치한다.
2. 상태가 정확히 `DONE`이다. `QUEUED`, `RUNNING`, `FAILED`는 허용하지 않는다.
3. 보관 종료 시각 `expiresAt`이 확인 가능하고 현재보다 미래다.
4. `deletedAt`이 없고 삭제된 결과가 아니다.
5. 결과 조회에서 비어 있지 않은 실제 결과 객체를 받았다.

매번 상태와 결과를 다시 확인하고, 결과 조회가 끝난 시점에도 보관 기한을 검사한다. 존재 여부 boolean을 저장하지 않는다. 결과 없음과 서버/통신 오류도 구분한다.

후속 연결 작업:

- 업로드 성공 시 Spring이 반환한 ID를 `rememberAnalysisId(analysisId)`에 전달한다. 세션에는 ID만 저장한다.
- Spring 어댑터의 `getAnalysis(id)`를 `GET /api/analyses/{analysisId}`에 연결한다.
- `getResult(id)`를 `GET /api/analyses/{analysisId}/result`에 연결한다.
- 명세서의 실제 응답 필드를 프런트엔드 계약 `{ analysisId, status, expiresAt, deletedAt }`으로 변환한다. 이 계약을 현재 서버 응답 형식이라고 가정하지 않는다.
- 404/410 또는 삭제된 결과는 `null`, 서버/통신 오류는 예외로 구분한다.
- `createAnalysisService({ backend: springAdapter })`로 만든 서비스를 App의 `analysisService` prop에 공급하거나 기본 인스턴스의 생성 위치에 연결한다.
- 프런트엔드에서 Python/FastAPI 서버를 직접 호출하지 않는다.

## 모달

`NoAnalysisModal.jsx`와 전용 CSS에 분리했다. 네이티브 `<dialog>.showModal()`을 사용하여 어두운 배경, 중앙 배치, 배경 inert 처리와 키보드 포커스 분리를 제공한다.

- ‘닫기’와 ESC의 native cancel 이벤트는 모달만 닫는다.
- URL 또는 브라우저 이력을 수정하지 않는다.
- 배경 스크롤을 잠근 뒤 기존 overflow/padding을 복구한다.
- 스크롤 위치를 바꾸지 않고 원래 버튼으로 포커스를 돌려준다.
- 중복 조회를 방지하며 다른 페이지로 이동한 뒤 도착한 오래된 조회 결과는 무시한다.

## 검증

- `npm.cmd test`: 2개 테스트 파일, 51개 테스트 통과.
- `npm.cmd run lint`: 통과.
- `npm.cmd run build`: 통과. 기존 시작 화면 PNG 및 홈 이미지 네 개가 각각 빌드에 포함됨.
- 개발 서버 `http://127.0.0.1:5173/home`: HTTP 200 확인.

자동 테스트는 jsdom에서 React Router 이동, 뒤로/앞으로 가기, 결과 조건 분기, 모달 닫기/cancel 이벤트, 포커스 복귀, 스크롤 위치 및 body 스타일 유지, 중복 조회 방지와 지연 응답 무시를 검증한다. 완료 결과 테스트 데이터는 테스트 파일에만 있다.

jsdom은 실제 레이아웃이나 네이티브 모달의 포커스 가두기를 구현하지 않는다. 실제 브라우저 시각 비교, 반응형 화면의 실측, 물리적 ESC 입력과 네이티브 inert 동작은 별도 브라우저 검증이 필요하다. 현재 브라우저 연결을 사용할 수 없어 해당 검증은 수행하지 않았다.

## 수정한 기존 파일

- `package.json`, `package-lock.json`: 테스트 개발 의존성과 test 스크립트
- `src/App.jsx`: 공통 분석 탐색 Provider와 여섯 경로
- `src/main.jsx`: 기존 900 굵기를 유지하면서 본문용 Noto Sans KR 400 추가
- `src/index.css`: 기존 파란색/회색 토큰과 버튼 포커스 스타일
- `src/components/Header.jsx`, `Header.css`: 공통 탐색 메뉴와 반응형 배치
- `src/pages/HomePage.jsx`, `HomePage.css`: 실제 홈 콘텐츠와 로컬 에셋

## 새 파일

- `src/routes/paths.js`
- `src/services/analysisService.js`, `analysisService.test.js`
- `src/features/analysis/AnalysisNavigationContext.js`
- `src/features/analysis/AnalysisNavigationProvider.jsx`
- `src/features/analysis/useAnalysisNavigation.js`
- `src/components/ActionLink.jsx`, `ActionButton.css`
- `src/components/ResultButton.jsx`
- `src/components/NoAnalysisModal.jsx`, `NoAnalysisModal.css`
- `src/components/PagePlaceholder.jsx`, `PagePlaceholder.css`
- `src/pages/UploadPage.jsx`
- `src/pages/ResultsPage.jsx`
- `src/pages/CheckItemsPage.jsx`
- `src/pages/GuidePage.jsx`
- `src/App.test.jsx`, `src/test/setup.js`
- `vitest.config.js`
- `docs/frontend-implementation.md`
