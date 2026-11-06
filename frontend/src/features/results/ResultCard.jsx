import { checkItemNames, sourceLabel } from './resultModel.js'

function ResultCard({ card, onShowClause, canShowClause }) {
  const verified = card.display === 'FULL' && !!card.title && !!card.explanation
  return (
    <article className="result-card">
      <div className="result-card__meta"><strong>{card.importance === 'HIGH' ? '꼭 확인' : '확인'}</strong><span>{sourceLabel(card.source)}</span></div>
      <h3>{verified ? card.title : checkItemNames[card.checkItem - 1] || '약관 원문'}</h3>
      {verified ? <div className="result-card__explanation"><h4>AI 쉬운 해설</h4><p>{card.explanation}</p></div> : <p className="result-card__quote-only">원문 확인 필요 · 검증되지 않은 해설은 표시하지 않습니다.</p>}
      <blockquote>{card.quote || '원문 인용이 확인되지 않았습니다.'}</blockquote>
      {card.fromTable && <p className="result-card__table-note">표 내용이므로 원문 확인 권장</p>}
      <div className="result-card__action"><button type="button" className="workflow-link" onClick={() => onShowClause(card)} disabled={!canShowClause || !card.clauseId}>원문 보기</button></div>
    </article>
  )
}

export default ResultCard
