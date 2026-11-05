import analysisImage from '../../assets/AI 분석 보고서 검토.png'
import checkIcon from '../../assets/미니멀 파란 체크박스 아이콘.png'
import { getAnalysisProgress, getUploadError } from './uploadModel.js'

const stageLabels = { done: '완료', running: '진행 중', waiting: '대기 중', failed: '실패' }

function AnalysisProgress({ state, progress, onRetryStatus, onRetryResult, onShowResults }) {
  const { analysis, result, isResultLoading, resultError, pollError } = state
  const { percent, stages } = progress || getAnalysisProgress(analysis)
  const failed = analysis.status === 'FAILED'
  const failure = failed ? getUploadError(analysis.error || { code: 'ANALYSIS_FAILED' }) : null
  const current = stages.find((stage) => stage.state === 'running')
  return (
    <>
      <img className="analysis-progress__image" src={analysisImage} alt="AI 분석 보고서 검토" width="379" height="306" />
      <p className="analysis-modal__subtitle analysis-progress__subtitle">선택하신 특약과 주계약을 검토하고 있습니다<br />분석이 완료될 때까지 잠시 기다려 주세요</p>
      <div className="analysis-progress__heading"><span>분석 진행 상황</span><span aria-live="polite">{failed ? '분석 실패' : analysis.status === 'DONE' ? '분석 완료' : analysis.status === 'QUEUED' ? '분석 대기 중' : current?.name || '진행 상태 확인 중'}</span></div>
      <div className="analysis-progress__bar" role="progressbar" aria-label="단계별 예상 진행률" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100} aria-valuetext={`단계별 예상 진행률 ${percent}%`}>
        <span style={{ width: `${percent}%` }} />
      </div>
      <p className="analysis-progress__estimate">단계별 예상 진행률 {percent}% · 실제 작업량 기준의 정확한 퍼센트가 아닙니다.</p>
      <ol className="analysis-progress__stages" aria-label="분석 단계">
        {stages.map((stage) => <li key={stage.name}>
          <span className="analysis-progress__marker">{stage.state === 'done' && <img src={checkIcon} alt="" width="93" height="62" />}</span>
          <span>{stage.name}</span><span className={`stage-badge stage-badge--${stage.state}`}>{stageLabels[stage.state]}</span>
        </li>)}
      </ol>
      {failure && <p className="workflow-error" role="alert">{failure.instruction} {failure.message}<br />오류 코드: {failure.code}</p>}
      {pollError && <div className="workflow-error" role="alert"><p>{pollError.instruction} 서버에서 시작된 작업은 계속될 수 있습니다.</p><button type="button" className="workflow-link" onClick={onRetryStatus}>상태 다시 확인</button></div>}
      {resultError && <div className="workflow-error" role="alert"><p>{resultError.instruction}</p><button type="button" className="workflow-link" onClick={onRetryResult}>결과 다시 불러오기</button></div>}
      {isResultLoading && <p role="status">분석 결과를 불러오고 있습니다.</p>}
      <div className="analysis-modal__action analysis-progress__action">
        <button type="button" className="workflow-link" disabled={analysis.status !== 'DONE' || !result || isResultLoading} onClick={onShowResults}>결과 확인하기 <span aria-hidden="true">›</span></button>
      </div>
    </>
  )
}

export default AnalysisProgress
