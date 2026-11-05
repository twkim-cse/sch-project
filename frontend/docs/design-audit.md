# 들다 디자인·기능 점검

이 문서는 전체 업로드 흐름 구현 **이전**의 점검 기록이다. 이후 Frame 17~27·50·45와 실제 HTTP 클라이언트가 구현되었으며, 현재 기능·연결 설정·검증 결과는 [업로드 흐름 구현 기록](./upload-flow.md)을 확인한다.

점검 대상은 `C:\HAKSUL`의 실제 소스다. 새 프로젝트나 업로드·분석 서버 기능을 생성하는 작업이 아니다.

## 수정 전 현재 상태

| URL | React 페이지 | 현재 상태 |
| --- | --- | --- |
| `/` | LandingPage | Frame 46 구현 |
| `/home` | HomePage | 홈 소개 4개 영역 구현, 원본과 시각 값 차이 |
| `/upload` | UploadPage | 안내 문구만 있는 임시 화면, PDF 선택·업로드 없음 |
| `/results` | ResultsPage | 안내 문구만 있는 임시 화면, 결과 조회·카드 없음 |
| `/check-items` | CheckItemsPage | 임시 화면과 업로드 이동 버튼 |
| `/guide` | GuidePage | 임시 화면과 업로드 이동 버튼 |

Frame 24·25·26·27·49·50의 React 컴포넌트나 상태 흐름은 없다. 따라서 기존 정상 기능이 있었다고 가정할 수 없다. BrowserRouter는 진입점 한 곳, Routes는 App 한 곳에 있으며 중복 경로는 없다. PagePlaceholder는 의도적으로 공유하는 공통 레이아웃이다.

## 원본 확인

사용자가 지정한 14개 노드의 `get_design_context` 코드와 Figma 스크린샷을 조회했다. `use_figma` 읽기 전용 조회로 실제 프레임 크기도 확인했다. 원본 Figma는 수정하지 않았다.

| 프레임 | 노드 | 실제 프레임 크기 |
| --- | --- | --- |
| Frame 46 | 98:331 | 1920×1080 |
| Desktop - 3 | 73:327 | 1920×1080 |
| Desktop - 1 | 47:24 | 1440×3050 |
| Frame 17 | 78:475 | 1920×1080 |
| Frame 24 | 78:506 | 1183×839 |
| Frame 25 | 78:510 | 1183×839 |
| Frame 26 | 86:37 | 1183×839 |
| Frame 27 | 92:112 | 1183×839 |
| Frame 45 | 98:322 | 1920×1080, 내부 1920×2549 |
| Frame 49 | 98:435 | 1183×839, 내부 원문 콘텐츠 높이 1422 |
| Frame 50 | 98:469 | 1295×502 |
| Frame 51 | 98:481 | 1295×502 |
| Desktop - 5 | 98:536 | 1920×1080, 내부 1920×5669 |
| Frame 52 | 98:540 | 1920×1080 |

## 수정 전 디자인 차이와 원인

| 대상 | 확인한 차이 | 원인 / 수정 방향 |
| --- | --- | --- |
| 공통 Header | 20px 메뉴와 파란 캡슐형 홈 버튼, 페이지 폭에 걸친 배치 | 시작 화면의 스타일을 다른 화면에도 적용함. 시작 화면은 유지하고 홈 전용 1440px Header 및 일반 페이지 Header 구분 |
| Frame 46 | 제목·설명 행간 1.3, 설명 너비가 유동적 | 원본 설명 텍스트 상자는 433×58. 데스크톱 너비와 행간을 원본 기준으로 조정 |
| Desktop - 3 / 1 | 제목 48/44px, 회색 설명, 작은 이미지와 680px 소개 영역 | 원본을 확인할 수 없던 이전 단계의 임시 시각 값. 89/100px 제목, 주황색 설명, 이미지 크기·투명도·위치와 630px 영역으로 조정 |
| Frame 51 | 520px 폭, 다른 제목·설명, 하단 파란 닫기 버튼 | 기능 우선 기본 모달. 원본 1295×502, 20px 반경, 상단 오른쪽 텍스트 닫기와 원문 문구로 수정 |
| Desktop - 5 | 6개 소개 영역 없음 | 상세 화면이 아직 임시 페이지임. 서버 기능과 무관한 안내 콘텐츠를 원본에 맞게 반영 |
| Frame 52 | 안내 카드 4개와 오른쪽 일러스트 없음 | 상세 화면이 아직 임시 페이지임. 로컬 원본 이미지와 안내 카드를 반영 |
| Frame 17 / 45 | 업로드 UI·데이터 기반 결과 UI 없음 | 해당 작업의 실제 코드가 이 경로에 없음. 기존 기능을 수정한 것으로 보고하지 않음 |

## Vite 기본 CSS

`#root`의 Vite 기본 max-width/padding/text-align 스타일은 없다. body margin은 0, 전역 box-sizing은 border-box이며 body에 강제 flex 또는 중앙 정렬도 없다. `.page-shell`의 max-width 1920px는 기존 공통 디자인 값이다. 홈의 중앙 정렬은 1440px 내부 콘텐츠에만 적용한다. 사진 크롭과 모달 내부 스크롤 외에 페이지 전체를 자르는 overflow는 없다. 충돌하는 Vite 기본 CSS가 없어 전체 CSS 초기화는 하지 않는다.

## 이미지 검사

기존 사진, 로고, 보고서, 방패, 안내서, 뇌 PNG 모두 Figma 원본 다운로드와 SHA-256 해시가 같다. 각각의 import 경로도 존재한다. 아이콘을 포함한 기존 이미지 7개는 수정·삭제하지 않는다. 로고 비율은 2:1, 사진은 원본 crop을 유지하고, 홈 일러스트에는 원본 레이어의 크기와 opacity를 적용한다. 투명 PNG를 화면 전체 스크린샷으로 대체하지 않는다.

## 렌더링 검증의 제한

현재 Computer Use 브라우저 검색 결과가 `Browsers: Error: The browser access host is unavailable`이다. 따라서 실제 React 스크린샷, 화면 크기별 실측, 이미지 슬롯의 최종 렌더링 위치와 원본 대비 시각 일치는 **미검증**이다. CSS·DOM 테스트나 HTTP 200을 시각 검증으로 취급하지 않는다.

## 수정 후 결과

아래 표의 **원본 반영**은 Figma 추출 코드·노드 속성에 맞춰 코드를 수정했다는 뜻이다. 실제 브라우저 스크린샷으로 일치 여부를 확인했다는 뜻이 아니다. 모든 프레임의 React 시각 검증은 미검증이다.

| 프레임 | 발견한 디자인 차이 | 발견한 기능 오류 / 누락 | 원인 | 수정 내용 | 수정 파일 | 검증 결과 |
| --- | --- | --- | --- | --- | --- | --- |
| [Frame 46](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-331) | 설명 폭 유동적, 두 텍스트의 행간 차이 | 로고·홈 이동 정상 | 고정 원본 텍스트 상자를 유동 배치로 근사함 | 데스크톱 설명 폭 433px, 행간 1.2. 기존 1806×736 사진 crop 및 시작 화면 Header 유지 | `LandingPage.css`, 공통 폰트 | 라우팅 DOM 테스트 통과, 이미지 파일 원본 일치, 실화면 미검증 |
| [Desktop - 3](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=73-327) | Header가 1920px 바깥 폭에 있고 홈 버튼이 캡슐 형태 | 기존 탐색 버튼은 정상 | 시작 화면 Header 스타일을 재사용함 | 홈 페이지 전체를 최대 1440px 중앙에 배치, Header 높이 96px, 로고 x19/y23, 325px 메뉴 폭 및 원본 시작 위치, 16px 텍스트 홈 링크 적용 | `Header.jsx`, `Header.css`, `HomePage.css` | 메뉴·로고·홈·이력 이동 테스트 통과, 실화면 미검증 |
| [Desktop - 1](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=47-24) | 제목 크기·설명 색·그림 크기·투명도·섹션 높이 차이 | 소개 링크는 정상 | 원본 조회 전 임시 시각 값 | 89px/100px 제목, 주황색 설명, 350/500/700/900 굵기, 뇌 654×545, 소개 영역 630px와 29px 간격, 그림 opacity·crop 반영. Header 포함 기준 3050px 콘텐츠를 문서 흐름으로 유지 | `HomePage.jsx`, `HomePage.css`, `main.jsx`, `index.css` | 4개 이미지·3개 소개 영역·CTA DOM 테스트 통과. 스크롤 실측 미검증 |
| [Frame 17](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=78-475) | 현재 `/upload`에 원본의 문서 스택·드롭 영역·개인정보 안내 없음 | PDF 선택·업로드 자체가 없음 | 이 프로젝트에는 임시 페이지만 존재 | 공통 Header의 원본 배치 보정만 적용. 기존 취소 버튼은 없으며 임의로 추가하지 않음. 업로드 UI·API·후속 흐름은 미구현으로 유지 | `Header.jsx`, `Header.css` | 이동 테스트 통과. PDF 업로드와 서버 연동 미검증/미구현 |
| [Frame 24](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=78-506) | 문서 확인 중 화면 없음 | 문서 상태 조회·오버레이 없음 | 컴포넌트·상태 흐름이 구현되지 않음 | 미수정. 1183×839, 제목 40px, 내부 960×470 영역을 원본에서 확인함 | 해당 구현 파일 없음 | 원본 조회 완료, React 화면/기능 미구현 |
| [Frame 25](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=78-510) | 문서 확인 완료 화면 없음 | productName/versionLabel/pageCount/riders/expiresAt 표시 없음 | 컴포넌트·응답 처리 없음. React에서 Frame 24와 잘못 연결된 참조도 없음 | 미수정. 최상위 78:506과 78:510이 서로 다른 노드임을 확인 | 해당 구현 파일 없음 | 원본 조회 완료, 응답값 렌더링 미검증/미구현 |
| [Frame 26](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=86-37) | 특약 선택 화면 없음 | 주계약 고정 포함·riders·전체 보기·체크 상태 없음 | 선택 상태 및 분석 생성 흐름 없음 | 미수정. 원본 30×30 체크박스와 필수 포함 영역 확인. 가짜 특약을 생성하지 않음 | 해당 구현 파일 없음 | 원본 조회 완료, React 화면/기능 미구현 |
| [Frame 27](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=92-112) | 분석 진행 화면 없음 | 단계 상태·stepIndex/stepCount·DONE 시 활성화 없음 | 분석 폴링/진행 컴포넌트 없음 | 미수정. 임의 타이머나 가짜 진행률은 추가하지 않음 | 해당 구현 파일 없음 | 원본 조회 완료, 진행 흐름 미검증/미구현 |
| [Frame 45](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-322) | `/results`가 임시 문구뿐임 | groups/checkSummary/QUOTE_ONLY/찾지 못함/원문 보기/한계 고지 표시 없음 | 결과 데이터 기반 UI 및 실제 API 없음 | 공통 Header 위치만 보정. 가상 상품·고정 결과 카드를 실제 결과처럼 넣지 않음. 내부 2549px 결과 콘텐츠는 미구현 | `Header.jsx`, `Header.css` | 유효 DONE 결과에 대한 이동 분기 테스트 통과. 실제 결과 표시 미구현 |
| [Frame 49](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-435) | 원문 보기 화면 없음 | clauseId를 사용하는 원문 조회·닫기 흐름 없음 | 결과 카드 및 조항 API 자체가 없음 | 미수정. 외부 1183×839와 내부 1422px 높이 원문 영역을 확인 | 해당 구현 파일 없음 | 원본 조회 완료, React 화면/기능 미구현 |
| [Frame 50](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-469) | 파일 오류 팝업 없음 | 재시도·/upload 복귀 흐름 없음 | 업로드 기능·실패 상태 없음 | 미수정. Frame 51과 별도 노드이며 원본 1295×502임을 확인 | 해당 구현 파일 없음 | 원본 조회 완료, React 화면/기능 미구현 |
| [Frame 51](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-481) | 크기·문구·반경·닫기 버튼 위치 차이 | 뒤로/앞으로 이동 후 예전 팝업 재등장, 같은 이력 항목으로 돌아오면 오래된 조회가 이동을 실행 | 페이지 이력 key를 재방문해도 Provider의 예전 상태가 살아 있음 | 1295×502, 반경 20px, 48px 제목, 20px 보조 문구, 상단 우측 닫기를 원본 반영. 이력 항목 변경 때 탐색 세션을 새로 마운트하여 예전 팝업·조회 폐기 | `NoAnalysisModal.jsx`, `NoAnalysisModal.css`, `AnalysisNavigationProvider.jsx`, `App.test.jsx` | 수정 전 2개 회귀 테스트 실패를 재현했고 수정 후 모두 통과. 닫기/ESC cancel·URL·포커스 복귀·스크롤 유지 DOM 테스트 통과. 네이티브 브라우저 동작 미검증 |
| [Desktop - 5](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-536) | 임시 화면이라 6개 항목과 긴 콘텐츠 없음 | 하단 업로드 이동은 정상 | 이전 작업 범위가 임시 페이지였음 | 원본 설명·계층·이미지·간격을 안내 페이지에 반영, 데스크톱 기준 5669px 문서 흐름과 좁은 화면 재배치. 제목은 최신 요청의 쉬운 명칭 6개를 원본의 기술 용어보다 우선 적용. 하단 문구는 기존 ‘약관 분석하기’ 유지 | `CheckItemsPage.jsx`, `CheckItemsPage.css`, `checkItems.js` | 6개 항목·설명·업로드 이동 테스트 통과. 전체 스크롤과 원본 시각 비교 미검증 |
| [Frame 52](https://www.figma.com/design/ujHNByAF2zgPLKwMZaoEq9?node-id=98-540) | 안내 카드 4개와 일러스트 없음 | 업로드 이동은 정상 | 이전 작업 범위가 임시 페이지였음 | 원본의 4개 안내 카드·오른쪽 로컬 안내서·48px 제목·주황색 소제목·CTA를 반영. 좁은 화면은 세로 배치 | `GuidePage.jsx`, `GuidePage.css` | 카드 4개·로컬 이미지·CTA 이동 테스트 통과. 실화면 미검증 |

## 기능 검사와 API 상태

- 기존 51개 테스트가 수정 전 모두 통과했다.
- 이력 이동 문제 2개를 추가 테스트로 재현했으며 수정 전 두 테스트가 실패했다. 수정 후 두 테스트와 기존 테스트가 통과했다.
- 추가 안내 페이지의 6개 항목과 4개 카드 검증을 포함하여 최종 테스트는 **2개 파일, 55개 모두 통과**했다.
- `npm.cmd run lint`: 통과.
- 최종 `npm.cmd run build -- --logLevel warn`: 통과.
- 개발 서버의 6개 URL이 모두 HTTP 200이며 수정된 진입점·페이지·Header·모달 JSX 모듈도 `text/javascript`로 HTTP 200이다. 이것만으로 실화면 정상이라고 판정하지 않는다.
- 기존 PNG 7개 모두 작업 전후 SHA-256 해시가 같고 삭제된 파일도 없다. 그중 화면에서 사용하는 6개는 Figma 원본 파일과도 해시가 일치한다.
- 세션 저장은 `deulda.analysisId` 참조만 저장한다. 결과 존재 boolean을 저장하지 않으며, 분석 검사 로직은 변경하지 않았다.
- 실제 `fetch`/Axios API 클라이언트, 백엔드 URL 설정, 프록시 및 Mock 실행 모드는 없다. `createAnalysisService()`의 기본 `backend`는 `null`이다. 테스트의 주입 데이터는 운영 화면의 실제 응답이 아니다.
- `POST /api/documents`, `GET /api/documents/{documentId}`, `POST /api/analyses`, 결과/조항 조회 API의 실제 성공·실패 회귀 검증은 **미검증**이다. 서버가 중지되었다고 단정한 것이 아니라, 이 프런트엔드에 연결 코드와 URL이 없는 상태다.
- 요청서의 API 및 상태 조건을 기준으로 코드를 검사했다. 로컬 명세 PDF 파일은 존재하지만 현재 Python/PDF CLI 실행 환경이 없어 본문 전체와 실제 서버 응답 스키마의 대조는 수행하지 못했다. 자체 정규화 계약을 실제 서버 스키마로 단정하지 않는다.

## 수정 파일

기존 파일:

- `src/components/Header.jsx`, `src/components/Header.css`
- `src/components/NoAnalysisModal.jsx`, `src/components/NoAnalysisModal.css`
- `src/features/analysis/AnalysisNavigationProvider.jsx`
- `src/pages/LandingPage.css`
- `src/pages/HomePage.jsx`, `src/pages/HomePage.css`
- `src/pages/CheckItemsPage.jsx`
- `src/pages/GuidePage.jsx`
- `src/main.jsx`, `src/index.css`
- `src/App.test.jsx`
- `package.json`, `package-lock.json`
- `docs/frontend-implementation.md`

새 파일:

- `src/pages/CheckItemsPage.css`
- `src/pages/GuidePage.css`
- `src/data/checkItems.js`
- `docs/design-audit.md`

Figma 350(DemiLight) 굵기를 포함한 연속 굵기를 지원하기 위해 `@fontsource-variable/noto-sans-kr`를 사용한다. 기존 Noto Sans KR 패키지를 삭제하지 않았고 Tailwind를 설치하지 않았다. 기존 `App.jsx`, `App.css`, 경로 정의, 분석 검사 서비스 및 원본 이미지 파일은 유지했다.

## 남은 작업

1. 실제 React 렌더링을 1920×1080 및 좁은 화면에서 캡처하고 원본과 겹쳐 비교해야 한다. 코드에서 정한 위치·폭·높이만으로 최종 렌더링 일치를 보장할 수 없다.
2. 다른 경로에 최신 업로드·분석 구현이 있다면 해당 소스로 다시 회귀 점검해야 한다. 현재 경로에는 그 구현이 없어 없는 기능을 복구했다고 보고할 수 없다.
3. `/upload`, `/results`, Frame 24/25/26/27/49/50을 실제 구현하려면 별도 기능 작업과 정확한 Spring 응답 계약·접속 정보가 필요하다. 현재 감사 범위에서 가짜 업로드·가짜 진행·가짜 분석 결과로 대체하지 않았다.
4. Frame 17을 구현할 경우 원본의 ‘투명 배경의 보험 서류 스택’(98:354) 이미지가 현재 로컬 에셋에 없으므로 해당 원본을 로컬로 확보해야 한다. 기존 홈용 원본 이미지에는 추가 확보가 필요하지 않다.
