import { lazy, Suspense } from 'react'
import { Route, Routes, useLocation } from 'react-router-dom'
import LandingPage from './pages/LandingPage.jsx'
import HomePage from './pages/HomePage.jsx'
import UploadPage from './pages/UploadPage.jsx'
import ResultsPage from './pages/ResultsPage.jsx'
import CheckItemsPage from './pages/CheckItemsPage.jsx'
import GuidePage from './pages/GuidePage.jsx'
import AnalysisNavigationProvider from './features/analysis/AnalysisNavigationProvider.jsx'
import AnalysisSessionProvider from './features/analysis/AnalysisSessionProvider.jsx'
import { paths } from './routes/paths.js'
import './App.css'

const PreviewModeProvider = import.meta.env.DEV
  ? lazy(() => import('./features/preview/PreviewModeProvider.jsx'))
  : null

function Application({ analysisService, api, persistReferences = true, children }) {
  return (
    <AnalysisSessionProvider api={api} analysisService={analysisService} persistReferences={persistReferences}>
      <AnalysisNavigationProvider>
        <Routes>
          <Route path={paths.landing} element={<LandingPage />} />
          <Route path={paths.home} element={<HomePage />} />
          <Route path={paths.upload} element={<UploadPage />} />
          <Route path={paths.results} element={<ResultsPage />} />
          <Route path={paths.checkItems} element={<CheckItemsPage />} />
          <Route path={paths.guide} element={<GuidePage />} />
        </Routes>
        {children}
      </AnalysisNavigationProvider>
    </AnalysisSessionProvider>
  )
}

function App(props) {
  const location = useLocation()
  if (import.meta.env.DEV && new URLSearchParams(location.search).get('preview') === '1') {
    return <Suspense fallback={<p role="status">개발용 미리보기를 준비하고 있습니다.</p>}><PreviewModeProvider Application={Application} /></Suspense>
  }
  return <Application {...props} />
}

export default App
