import { useState } from 'react'
import { paths } from '../../routes/paths.js'
import { useAppNavigate } from './previewRouting.js'
import { usePreviewMode } from './usePreviewMode.js'

const destinations = [
  ['46', 'Frame 46 - 시작 화면', paths.landing],
  ['home', 'Desktop-3 - 홈 화면', paths.home],
  ['checks', 'Desktop-5 - 점검 항목', paths.checkItems],
  ['guide', 'Frame 52 - 이용 가이드', paths.guide],
  ['17', 'Frame 17 - PDF 업로드', paths.upload],
  ['24', 'Frame 24 - 문서 확인 중', paths.upload],
  ['25', 'Frame 25 - 문서 확인 완료', paths.upload],
  ['26', 'Frame 26 - 가입한 특약 선택', paths.upload],
  ['27', 'Frame 27 - AI 분석 진행', paths.upload],
  ['45', 'Frame 45 - 분석 결과', paths.results],
  ['49', 'Frame 49 - 원문 보기', paths.results],
  ['50', 'Frame 50 - 파일 오류', paths.upload],
  ['51', 'Frame 51 - 분석 결과 없음', paths.home],
  ['53', 'Frame 53 - 분석 데이터 삭제 확인', paths.results],
]

function PreviewNavigation({ global = false }) {
  const { controller } = usePreviewMode()
  const navigate = useAppNavigate()
  const [expanded, setExpanded] = useState(false)

  function openFrame(id, pathname) {
    if (['45', '49', '53'].includes(id)) controller.restore()
    const search = new URLSearchParams()
    if (['24', '25', '26', '27', '49', '50', '51', '53'].includes(id)) search.set('previewFrame', id)
    navigate({ pathname, search: search.toString() })
  }

  return (
    <aside className={`preview-navigation${global ? ' preview-navigation--global' : ' preview-navigation--modal'}`} aria-label="개발자 미리보기 메뉴">
      <button type="button" className="preview-navigation__toggle" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>개발자 미리보기 <span aria-hidden="true">{expanded ? '−' : '+'}</span></button>
      {expanded && <nav aria-label="미리보기 화면 선택">
        <p>가상 데이터 · 실제 서버 요청 없음</p>
        {destinations.map(([id, label, pathname]) => <button type="button" key={id} onClick={() => openFrame(id, pathname)}>{label}</button>)}
        <a href={paths.landing}>미리보기 종료 · 일반 모드</a>
      </nav>}
    </aside>
  )
}

export default PreviewNavigation
