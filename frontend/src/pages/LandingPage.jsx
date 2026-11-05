import Header from '../components/Header.jsx'
import insuranceReview from '../assets/insurance-review.png'
import './LandingPage.css'

function LandingPage() {
  return (
    <div className="page-shell">
      <Header />
      <main className="landing" aria-labelledby="landing-title">
        <div className="landing__introduction">
          <h1 id="landing-title" className="landing__title">
            내가 가입한 보험<br />
            제대로 알고 계신가요?
          </h1>
          <p className="landing__description">
            <span>읽어도 이해하기 어려웠던 보험약관,</span>
            <span>이제 AI와 함께 쉽고 명확하게 확인해 보세요</span>
          </p>
        </div>
        <div className="landing__photo">
          <img
            src={insuranceReview}
            alt="보험 서류를 읽으며 고민하는 남성"
            width="1487"
            height="1058"
            fetchPriority="high"
          />
        </div>
      </main>
    </div>
  )
}

export default LandingPage
