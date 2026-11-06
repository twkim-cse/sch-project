import { useEffect, useState } from 'react'
import AnalysisModal from '../../components/AnalysisModal.jsx'
import { assertClause, splitQuote, sourceLabel } from './resultModel.js'
import divider from '../../assets/clause-divider.svg'

function ClauseModal({ api, documentId, card, onClose }) {
  const [state, setState] = useState({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    async function load() {
      try {
        const clause = assertClause(await api.getClause(documentId, card.clauseId, { signal: controller.signal }), card.clauseId)
        if (active) setState({ status: 'ready', clause })
      } catch (error) {
        if (active && error.name !== 'AbortError') setState({ status: 'error', message: error.message })
      }
    }
    void load()
    return () => { active = false; controller.abort() }
  }, [api, documentId, card.clauseId, attempt])
  const parts = state.clause ? splitQuote(state.clause.text, card.quote) : null
  return (
    <AnalysisModal title="원문 보기" onClose={onClose} className="clause-modal">
      <img className="clause-modal__divider" src={divider} alt="" />
      {state.status === 'loading' && <p role="status">원문을 불러오고 있습니다.</p>}
      {state.status === 'error' && <div role="alert"><p>{state.message}</p><button type="button" className="workflow-link" onClick={() => { setState({ status: 'loading' }); setAttempt((value) => value + 1) }}>다시 불러오기</button></div>}
      {state.status === 'ready' && <>
        <p className="clause-modal__source"><strong>{card.importance === 'HIGH' ? '꼭 확인' : '확인'}</strong><span>{sourceLabel(state.clause)}</span></p>
        <h3>{state.clause.articleTitle || '약관 원문'}</h3>
        <div className="analysis-modal__panel clause-modal__text"><h4>약관 원문</h4><p>{parts.before}{parts.quote && <mark>{parts.quote}</mark>}{parts.after}</p></div>
        {!parts.quote && <p>인용문의 위치를 확인하지 못했습니다. 원문 전체를 직접 확인해 주세요.</p>}
        {card.display === 'FULL' && card.title && card.explanation ? <div className="clause-modal__explanation"><h3>AI 쉬운 해설</h3><p>{card.explanation}</p></div> : <p>원문 확인 필요 · 검증되지 않은 해설은 표시하지 않습니다.</p>}
      </>}
    </AnalysisModal>
  )
}

export default ClauseModal
