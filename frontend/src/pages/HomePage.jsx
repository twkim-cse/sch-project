import Header from '../components/Header.jsx'
import ActionLink from '../components/ActionLink.jsx'
import ResultButton from '../components/ResultButton.jsx'
import { paths } from '../routes/paths.js'
import brainImage from '../assets/화이트 타일 위 파란 뇌.png'
import resultImage from '../assets/AI 분석 보고서 검토.png'
import checkItemsImage from '../assets/보험 체크리스트와 보안 방패 (1).png'
import guideImage from '../assets/파란 도움 아이콘이 있는 열린 안내서.png'
import './HomePage.css'

function HomeFeature({ id, title, description, image, imageAlt, variant, children }) {
  const fitTitle = variant === 'results' || variant === 'check-items'
  return (
    <section className={`home-feature home-feature--${variant}${fitTitle ? ' home-feature--title-fit' : ''}`} aria-labelledby={id}>
      <div className="home-feature__copy">
        <h2 id={id} className={fitTitle ? 'home-feature__title--single-line' : undefined}>{title}</h2>
        <p>{description}</p>
        {children}
      </div>
      <img
        className="home-feature__image"
        src={image}
        alt={imageAlt}
        width="500"
        height="404"
        loading="lazy"
        decoding="async"
      />
    </section>
  )
}

function HomePage() {
  return (
    <div className="page-shell home-page">
      <Header />
      <main className="home-content" aria-labelledby="home-title">
        <section className="home-hero">
          <h1 id="home-title">내 보험약관 쉽게 알아보세요</h1>
          <p>
            복잡한 보험약관 속 중요한 조항을 ai가 찾아 설명해 드립니다
          </p>
          <img
            className="home-hero__image"
            src={brainImage}
            alt="흰색 타일 위의 파란색 AI 뇌"
            width="1374"
            height="1145"
            fetchPriority="high"
          />
          <ActionLink to={paths.upload}>약관 분석하기</ActionLink>
        </section>
        <HomeFeature
          id="home-results"
          variant="results"
          title="분석 결과 확인"
          description="보험약관의 AI 분석 결과를 한눈에 확인해 보세요."
          image={resultImage}
          imageAlt="돋보기로 보험약관 분석 보고서를 검토하는 이미지"
        >
          <ResultButton className="action-button action-button--secondary">
            더 알아보기
          </ResultButton>
        </HomeFeature>
        <HomeFeature
          id="home-check-items"
          variant="check-items"
          title="핵심 점검 항목"
          description="6가지 핵심 항목을 중심으로 약관의 주요 내용을 꼼꼼하게"
          image={checkItemsImage}
          imageAlt="보험 체크리스트와 파란색 보안 방패"
        >
          <ActionLink to={paths.checkItems} variant="secondary">더 알아보기</ActionLink>
        </HomeFeature>
        <HomeFeature
          id="home-guide"
          variant="guide"
          title="이용 가이드"
          description="보험약관 준비부터 결과까지 이용 방법을 안내합니다"
          image={guideImage}
          imageAlt="파란 도움 아이콘과 재생 버튼이 있는 열린 안내서"
        >
          <ActionLink to={paths.guide} variant="secondary">더 알아보기</ActionLink>
        </HomeFeature>
      </main>
    </div>
  )
}

export default HomePage
