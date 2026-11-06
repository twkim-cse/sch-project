import { Link, NavLink, useLocation } from 'react-router-dom'
import ResultButton from './ResultButton.jsx'
import { paths } from '../routes/paths.js'
import logo from '../assets/deulda-logo.png'
import './Header.css'
import { usePreviewTo } from '../features/preview/previewRouting.js'

function Header() {
  const to = usePreviewTo()
  const { pathname } = useLocation()
  const isLanding = pathname === paths.landing
  const isHome = pathname === paths.home
  const isDocumentPage = pathname === paths.upload || pathname === paths.results

  return (
    <header className={`site-header${isLanding ? '' : ' site-header--with-nav'}${isHome ? ' site-header--home' : ''}${isDocumentPage ? ' site-header--document' : ''}`}>
      <Link className="site-header__brand" to={to(paths.landing)} aria-label="들다 시작 화면">
        <img src={logo} alt="들다" width="90" height="45" />
      </Link>
      {!isLanding && (
        <nav className="site-header__navigation" aria-label="주 메뉴">
          <ResultButton className="site-header__nav-item">결과 확인</ResultButton>
          <NavLink className="site-header__nav-item" to={to(paths.checkItems)}>
            점검 항목
          </NavLink>
          <NavLink className="site-header__nav-item" to={to(paths.guide)}>
            이용 가이드
          </NavLink>
        </nav>
      )}
      <Link className="site-header__home" to={to(paths.home)}>
        {isLanding ? '홈으로' : '홈으로 가기'}
      </Link>
    </header>
  )
}

export default Header
