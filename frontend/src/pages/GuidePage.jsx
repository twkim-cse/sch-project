import ActionLink from '../components/ActionLink.jsx'
import Header from '../components/Header.jsx'
import guideImage from '../assets/파란 도움 아이콘이 있는 열린 안내서.png'
import { paths } from '../routes/paths.js'
import './GuidePage.css'

const steps = [
  ['보험약관 PDF 업로드', '가입한 보험의 약관 PDF 파일을 업로드합니다. AI가 분석할 수 있도록 문서 내용을 확인합니다'],
  ['보험 정보 확인', '문서에서 확인된 상품명과 개정 시기, 페이지 수를 확인하고 본인의 약관이 맞는지 검토합니다'],
  ['가입한 특약 선택', '분석할 특약을 선택합니다. 가입한 특약을 모른다면 전체 보기를 선택할 수 있습니다.'],
  ['AI 분석 결과 확인', '주의해야 할 조항과 쉬운 해설을 확인합니다. 원문 보기로 실제 약관 내용도 대조할 수 있습니다.'],
]

function GuidePage() {
  return (
    <div className="page-shell">
      <Header />
      <main className="guide-page" aria-labelledby="guide-title">
        <h1 id="guide-title">이용 가이드</h1>
        <div className="guide-page__layout">
          <ol className="guide-steps" aria-label="서비스 이용 순서">
            {steps.map(([title, description], index) => (
              <li className="guide-step" key={title}>
                <span className="guide-step__number" aria-hidden="true">{String(index + 1).padStart(2, '0')}</span>
                <h2>{title}</h2>
                <p>{description}</p>
              </li>
            ))}
          </ol>
          <img
            className="guide-page__image"
            src={guideImage}
            alt="파란 도움 아이콘이 있는 열린 안내서"
            width="1004"
            height="811"
          />
        </div>
        <ActionLink to={paths.upload} className="guide-page__action">약관 분석하기</ActionLink>
      </main>
    </div>
  )
}

export default GuidePage
