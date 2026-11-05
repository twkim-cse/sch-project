import { useEffect, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { useAppNavigate, usePreviewTo } from '../features/preview/previewRouting.js'
import { usePreviewMode } from '../features/preview/usePreviewMode.js'
import Header from '../components/Header.jsx'
import ActionLink from '../components/ActionLink.jsx'
import { paths } from '../routes/paths.js'
import { useAnalysisSession } from '../features/analysis/useAnalysisSession.js'
import { readCurrentAnalysisId } from '../services/analysisService.js'
import { assertResult, displayValue, formatExpiry } from '../features/upload/uploadModel.js'
import { checkItemNames } from '../features/results/resultModel.js'
import ResultCard from '../features/results/ResultCard.jsx'
import ClauseModal from '../features/results/ClauseModal.jsx'
import DeleteAnalysisModal from '../features/results/DeleteAnalysisModal.jsx'
import './ResultsPage.css'

function ResultsPage() {
  const { api, service, completed, acceptCompleted, forgetAnalysis } = useAnalysisSession()
  const location = useLocation()
  const navigate = useAppNavigate()
  const to = usePreviewTo()
  const preview = usePreviewMode()
  const analysisId = preview.active ? preview.analysisId : location.state?.analysisId || completed?.analysis?.analysisId || readCurrentAnalysisId()
  const [load, setLoad] = useState({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const [view, setView] = useState('all')
  const [importance, setImportance] = useState('ALL')
  const [clauseCard, setClauseCard] = useState(null)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [overlayDismissed, setOverlayDismissed] = useState(false)

  useEffect(() => {
    let active = true
    async function loadResult() {
      try {
        const value = analysisId ? await service.getCompletedAnalysis() : await Promise.resolve(null)
        if (!active) return
        if (value && value.analysis.analysisId === analysisId) {
          assertResult(value.result, analysisId)
          acceptCompleted(value)
          setLoad({ status: 'ready', bundle: value, preview: preview.active })
          return
        }
        setLoad({ status: 'empty' })
      } catch (error) {
        if (active) setLoad({ status: 'error', message: error.message })
      }
    }
    void loadResult()
    return () => { active = false }
  }, [analysisId, service, acceptCompleted, attempt, preview.active])

  const bundle = load.bundle
  const result = bundle?.result
  const groups = result ? [...result.groups].sort((a, b) => (a.scope === 'MAIN' ? 0 : 1) - (b.scope === 'MAIN' ? 0 : 1)) : []
  const cards = groups.flatMap((group) => group.cards)
  const documentId = bundle?.analysis.documentId || location.state?.documentId
  const summary = result ? [...result.checkSummary].sort((a, b) => a.checkItem - b.checkItem) : []
  const filtered = (card) => (view !== 'risk' || card.importance === 'HIGH') && (importance === 'ALL' || card.importance === importance)
  const hasCards = cards.some(filtered)
  const activeClause = clauseCard || (preview.frame === '49' && !overlayDismissed ? cards[0] : null)
  const showDelete = deleteOpen || (preview.frame === '53' && load.status === 'ready' && !overlayDismissed)

  async function deleteAnalysis() {
    if (load.preview) return
    await api.deleteDocument(documentId)
    forgetAnalysis(bundle.analysis.analysisId, documentId)
  }

  function finishDeletion() {
    if (preview.active) {
      preview.controller.clear()
      forgetAnalysis(bundle.analysis.analysisId, documentId)
    }
    setDeleteOpen(false)
    setClauseCard(null)
    setLoad({ status: 'empty' })
    navigate(paths.upload)
  }

  return (
    <div className="page-shell">
      <Header />
      <main className="results-page" aria-labelledby="results-title">
        <h1 id="results-title">보험약관 AI 분석 결과</h1>
        {load.status === 'loading' && <p role="status">분석 결과의 상태와 보관 기한을 확인하고 있습니다.</p>}
        {load.status === 'empty' && <div className="results-page__empty"><p>확인할 수 있는 완료된 분석 결과가 없습니다. 보관 기간이 지났거나 삭제된 결과일 수 있습니다.</p><ActionLink to={paths.upload}>약관 분석하기</ActionLink></div>}
        {load.status === 'error' && <div className="results-page__empty" role="alert"><p>{load.message}</p><button type="button" className="workflow-link" onClick={() => { setLoad({ status: 'loading' }); setAttempt((value) => value + 1) }}>결과 다시 불러오기</button></div>}
        {import.meta.env.DEV && ['empty', 'error'].includes(load.status) && <Link className="results-page__preview-link" to={`${paths.results}?preview=1`}>개발용 결과 미리보기</Link>}
        {load.status === 'ready' && <>
          {load.preview && <aside className="results-page__preview" aria-label="미리보기 데이터"><strong>미리보기 데이터</strong><p>개발용 가상 예시를 보고 있습니다. 실제 보험약관 분석 결과가 아닙니다.</p></aside>}
          <section className="result-summary" aria-label="분석 대상과 요약">
            <div className="result-info"><h2>분석한 보험 정보</h2><dl>
              <div><dt>상품명</dt><dd>{displayValue(result.document.productName)}</dd></div>
              <div><dt>약관 개정 시기</dt><dd>{displayValue(result.document.versionLabel)}</dd></div>
              <div><dt>분석 범위</dt><dd>공통 (주계약){result.viewAll ? ' · 모든 특약' : (result.selectedRiders || []).map((rider) => ` · ${displayValue(rider.name)}`).join('')}</dd></div>
              <div><dt>분석 페이지</dt><dd>{result.document.pageCount == null ? '확인되지 않음' : `${result.document.pageCount}쪽`}</dd></div>
            </dl><p>{load.preview ? '개발용 예시 · 실제 보관 기간 없음' : `보관 만료: ${formatExpiry(bundle.analysis.expiresAt)}`}</p></div>
            <div className="result-counter"><strong>{cards.length}</strong><span>확인된<br />주요사항</span></div>
            <div className="result-counter result-counter--warning"><strong>{cards.filter((card) => card.importance === 'HIGH').length}</strong><span>주의할<br />조항</span></div>
            <div className="result-counter result-counter--riders"><strong>{result.selectedRiders?.length ?? '—'}</strong><span>선택된<br />특약</span></div>
          </section>
          <div className="result-filters">
            <div className="result-filters__views" role="group" aria-label="결과 보기 방식">
              {[['risk', '위험 조항'], ['all', '전체 조항'], ['checks', '점검 항목']].map(([value, label]) => <button key={value} type="button" aria-pressed={view === value} onClick={() => setView(value)}>{label}</button>)}
            </div>
            <label>중요도 <select value={importance} onChange={(event) => setImportance(event.target.value)}><option value="ALL">전체</option><option value="HIGH">꼭 확인</option><option value="NORMAL">확인</option></select></label>
          </div>
          {view === 'checks' && <section className="result-check-summary" aria-label="6개 점검 항목 요약">
            {summary.map((item) => <div key={item.checkItem}><h2>{item.name || checkItemNames[item.checkItem - 1]}</h2><p>{item.status === 'NOT_FOUND' ? '찾지 못했습니다. 직접 확인하세요' : item.cardCount == null ? '개수 확인되지 않음' : `확인된 조항 ${item.cardCount}개`}</p></div>)}
          </section>}
          {view !== 'checks' && groups.map((group, index) => {
            const visible = [...group.cards].filter(filtered).sort((a, b) => a.checkItem - b.checkItem)
            return visible.length > 0 && <section className="result-group" key={`${group.scope}-${group.riderId || index}`} aria-label={group.name || '확인된 조항'}><h2>{group.name || (group.scope === 'MAIN' ? '공통 (주계약)' : '확인되지 않음')}</h2>{visible.map((card, cardIndex) => <ResultCard key={card.cardId || `${card.clauseId}-${cardIndex}`} card={card} canShowClause={!!documentId} onShowClause={setClauseCard} />)}</section>
          })}
          {view !== 'checks' && !hasCards && <p>선택한 조건에 맞는 조항이 없습니다.</p>}
          {view !== 'risk' && summary.filter((item) => item.status === 'NOT_FOUND').map((item) => <article className="result-card result-card--not-found" key={item.checkItem}><strong>찾지 못함</strong><h2>{item.name || checkItemNames[item.checkItem - 1]}</h2><p>찾지 못했습니다. 직접 확인하세요</p></article>)}
        </>}
        <p className="results-disclaimer">약관 해설이며 보험금 지급을 보장하거나 법률 자문을 대신하지 않습니다.</p>
        {load.status === 'ready' && <footer className="results-footer" aria-label="분석 결과 작업">
          <button type="button" className="results-footer__delete" onClick={() => setDeleteOpen(true)} disabled={!documentId}>분석결과 삭제하기</button>
          <Link className="results-footer__done" to={to(paths.upload)}>확인 완료</Link>
        </footer>}
      </main>
      {activeClause && <ClauseModal api={api} documentId={documentId} card={activeClause} onClose={() => { setClauseCard(null); setOverlayDismissed(true) }} />}
      {showDelete && <DeleteAnalysisModal preview={!!load.preview} onClose={() => { setDeleteOpen(false); setOverlayDismissed(true) }} onDelete={deleteAnalysis} onDeleted={finishDeletion} />}
    </div>
  )
}

export default ResultsPage
