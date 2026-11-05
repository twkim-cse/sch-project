import { useState } from 'react'
import { UploadScreen } from '../../pages/UploadPage.jsx'
import { getUploadError, validatePdfSelection } from '../upload/uploadModel.js'
import { previewDocument, previewErrors } from '../results/resultsPreview.js'
import { paths } from '../../routes/paths.js'
import { usePreviewMode } from './usePreviewMode.js'
import { useAppNavigate } from './previewRouting.js'

const stageNames = ['약관 텍스트 추출', '주요 조항 추출', '원문 인용 대조', '쉬운 말 해설 생성', '최종 검증 및 정리']
const phases = { 24: 'checking', 25: 'confirmation', 26: 'riders', 27: 'progress', 50: 'error' }
const initial = {
  phase: 'idle', file: null, document: previewDocument, riderIds: [], viewAll: false,
  analysis: null, result: null, error: null, pollError: null, resultError: null,
  isSubmitting: false, isResultLoading: false, notice: '', stage: 0,
}

function MockAnalysisFlow() {
  const { controller, frame, errorCode } = usePreviewMode()
  const navigate = useAppNavigate()
  const [state, setState] = useState(() => ({ ...initial, phase: phases[frame] || 'idle',
    analysis: frame === '27' ? { analysisId: 'preview-analysis', status: 'RUNNING' } : null,
    error: frame === '50' ? getUploadError({ code: previewErrors.some(([code]) => code === errorCode) ? errorCode : 'NOT_PDF' }) : null,
  }))

  function selectFiles(files) {
    if (state.phase !== 'idle') return
    const error = validatePdfSelection(files)
    setState({ ...initial, ...(error ? { phase: 'error', error: getUploadError(error) } : { file: files[0] }) })
  }

  function changeStage(stage) {
    const options = { riderIds: state.riderIds, viewAll: state.viewAll }
    if (stage === 5) controller.restore(options)
    else controller.clear()
    setState((previous) => ({ ...previous, stage, analysis: { analysisId: 'preview-analysis', status: stage === 5 ? 'DONE' : 'RUNNING' }, result: stage === 5 ? controller.getSnapshot().bundle.result : null }))
  }

  const progress = {
    percent: state.stage * 20,
    stages: stageNames.map((name, index) => ({ name, state: index < state.stage ? 'done' : index === state.stage ? 'running' : 'waiting' })),
  }

  const previewControls = <>
    <p className="preview-tools__badge">미리보기 데이터 · 가상 약관입니다. 실제 파일 전송이나 분석은 실행하지 않습니다.</p>
    {state.phase === 'checking' && <div className="preview-tools"><p>개발용 수동 문서 확인</p><button type="button" onClick={() => setState((previous) => ({ ...previous, phase: 'confirmation' }))}>문서 확인 완료</button></div>}
    {state.phase === 'progress' && <div className="preview-tools" role="group" aria-label="개발용 분석 단계 조절"><p>현재 단계와 완료 상태를 수동으로 변경할 수 있습니다.</p>{stageNames.map((name, index) => <button type="button" key={name} aria-pressed={state.stage === index} onClick={() => changeStage(index)}>{index + 1}. {name}</button>)}<button type="button" onClick={() => changeStage(Math.min(5, state.stage + 1))} disabled={state.stage === 5}>다음 단계</button><button type="button" onClick={() => changeStage(5)}>모든 단계 완료</button></div>}
    {state.phase === 'error' && <div className="preview-tools"><label>테스트 오류 선택 <select value={state.error.code} onChange={(event) => setState((previous) => ({ ...previous, error: getUploadError({ code: event.target.value }) }))}>{previewErrors.map(([code, label]) => <option value={code} key={code}>{label}</option>)}</select></label></div>}
  </>

  return <UploadScreen flow={{
    state, preview: true, previewControls, progress,
    selectFiles,
    close: () => setState(initial),
    startSample: () => setState({ ...initial, phase: 'checking' }),
    startUpload: () => { if (state.file && state.phase === 'idle') setState((previous) => ({ ...previous, phase: 'checking' })) },
    confirmDocument: () => setState((previous) => ({ ...previous, phase: 'riders' })),
    toggleRider: (id) => { if (!state.viewAll) setState((previous) => ({ ...previous, riderIds: previous.riderIds.includes(id) ? previous.riderIds.filter((value) => value !== id) : [...previous.riderIds, id] })) },
    toggleViewAll: () => setState((previous) => ({ ...previous, viewAll: !previous.viewAll })),
    startAnalysis: () => { controller.clear(); setState((previous) => ({ ...previous, phase: 'progress', stage: 0, analysis: { analysisId: 'preview-analysis', status: 'RUNNING' }, result: null })) },
    showResults: () => { if (state.analysis?.status === 'DONE' && state.result) navigate(paths.results) },
  }} />
}

export default MockAnalysisFlow
