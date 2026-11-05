import ActionLink from '../components/ActionLink.jsx'
import Header from '../components/Header.jsx'
import checkItemsImage from '../assets/보험 체크리스트와 보안 방패 (1).png'
import { checkItems } from '../data/checkItems.js'
import { paths } from '../routes/paths.js'
import './CheckItemsPage.css'

function CheckItemsPage() {
  return (
    <div className="page-shell">
      <Header />
      <main className="check-items-page" aria-labelledby="check-items-title">
        <section className="check-items-intro">
          <h1 id="check-items-title">복잡한 보험약관 핵심만 확인하세요</h1>
          <p>
            들다 AI는 보험약관에서 놓치기 쉬운 주요 조건을 찾아<br />
            쉬운 설명과 함께 원문 근거를 제공합니다
          </p>
          <img src={checkItemsImage} alt="보험 체크리스트와 보안 방패" width="355" height="287" />
        </section>
        {checkItems.map((item) => (
          <section className="check-item" key={item.id} aria-labelledby={`check-${item.id}`}>
            <h2 id={`check-${item.id}`}>{item.title}</h2>
            <p className="check-item__subtitle">{item.subtitle}</p>
            <p className="check-item__description">{item.description}</p>
          </section>
        ))}
        <div className="check-items-page__action">
          <ActionLink to={paths.upload}>약관 분석하러 가기</ActionLink>
        </div>
      </main>
    </div>
  )
}

export default CheckItemsPage
