# PR 검증 기록 — 2026-11-07

기준 main `49126ec`는 PR #17 병합 커밋이다. 이번 작업은 `fix/spring-lossless-extract`에서 진행했으며 다른 작업 트리/실제 자료/.env를 보존했다.

| 검증 | 결과 |
|---|---|
| AI 오프라인 unittest | 304개 통과 |
| Spring 전체 test (실제 JSON Mock opt-in 포함) | 99개 중96개 통과,3개 skip,실패0 |
| Spring 기본 test (실제 JSON 미제공) | 95개 통과,4개 skip |
| 전체 New Dental JSON plan/part/merge | 1,472조항,1,479fragment,77parts,모든 Mock part200,merge200,누락 merge409 |
| Spring HTTP → Mock AI → 해설·검토 → 저장·최종 조회 | 통과,전체77part 처리 |
| 별도 JVM 두 프로세스의 디스크 복구 | 통과,성공part 재사용,실제 Gemini0회 |
| Spring 재시작·명시적 재개·최종 조회 통합 | 통과,이미 성공한part 재전송 없음 |
| React 전체 테스트 worker1 | 314개 통과 |
| React Vite production build | 성공 |
| backend bootJar | 성공,JDK23 + release21 |
| 실제 Java21 런타임 / Docker build·run | 미검증,Docker CLI 없음 |
| 실제 Gemini 전체77 / React 실제 PDF 최종 E2E | 미검증 |

React 기본 병렬 test 실행에서는 기존 PreviewMode navigation 테스트1개가 한 번 실패했다(jsdom navigation 경고). 해당 파일 단독27개와 전체 worker1 314개는 통과했다. 프론트 코드/테스트를 변경하거나 실패를 숨기지 않았다. CI 기본 병렬 실행의 비결정적 실패 가능성은 프론트 담당자 추가 확인 대상이다.

이전 실제 소량 Gemini 추출·해설·독립 검토는 성공했으나 전체 분석 성공으로 일반화하지 않는다. 이번 PR 준비에서는 실제 Gemini 생성0회이다. 사용자 확인 무료 계정 한도 RPM5/TPM250,000/RPD20으로 전체 extract77회만으로도 일일 한도를 넘으므로 유료 계정 별도 승인·예산 확인 전 전체 실행하지 않는다.

413은 원문 전체 단일 요청의37,356byte 단일 조항 예산 초과로 재현했다. 무손실 계획 후 각part 최대25조항,입력최대23,945bytes이며 primary 원문과 메타데이터 재조립 검증이 통과했다. 원문 자체는 저장소에 포함하지 않는다.

503 복구는 Mock으로 제한적시도,SDK자동retry비활성화,backoff+jitter,Retry-After,cooldown,실패후중단,재개·시도예산보존을 검증했다. 외부 서비스 장애 자체를 해결했다는 주장은 하지 않는다. 실제 소량 성공 당시503이 없었으므로 실제 장애 복구 효과는 별도 검증이 필요하다.

일부 실패는 FAILED/PARTIAL이며 미처리 범위를 NOT_FOUND로 표시하지 않는다. 모든part 성공 후merge하고,독립검토 및 숫자검증을 거쳐야 최종DONE이다. review=fail은QUOTE_ONLY를 유지한다.

팀원 적용: PR리뷰/승인 후 AI·Spring 이미지 재빌드 및 재시작,`MOCK_DOCUMENTS=false`,기존키와`GEMINI_MODEL=gemini-3.6-flash` 전달,체크포인트volume 보존 및 접근권한제한. B안OFF 유지. 이번 작업에서는 main직접push/merge/배포하지 않는다. 상세는 [연동 절차](SPRING_INTEGRATION.md)와 [재시작 복구](DURABLE_RECOVERY.md)를 따른다.
