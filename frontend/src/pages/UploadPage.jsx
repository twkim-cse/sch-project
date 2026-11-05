import { useRef, useState } from 'react'
import Header from '../components/Header.jsx'
import AnalysisModal from '../components/AnalysisModal.jsx'
import DocumentChecking from '../features/upload/DocumentChecking.jsx'
import DocumentConfirmation from '../features/upload/DocumentConfirmation.jsx'
import RiderSelection from '../features/upload/RiderSelection.jsx'
import AnalysisProgress from '../features/upload/AnalysisProgress.jsx'
import UploadErrorModal from '../features/upload/UploadErrorModal.jsx'
import { useUploadFlow } from '../features/upload/useUploadFlow.js'
import { MAX_PDF_SIZE_MB } from '../config/analysis.js'
import documentImage from '../assets/insurance-documents.png'
import '../features/upload/UploadFlow.css'
import './UploadPage.css'
import { usePreviewMode } from '../features/preview/usePreviewMode.js'

const titles = {
  checking: '문서 확인중 ...', confirmation: '보험 약관 확인 완료',
  riders: '가입한 특약 선택', progress: 'AI가 보험약관을 분석하고 있습니다',
  error: '파일을 확인할 수 없습니다',
}

export function UploadScreen({ flow }) {
  const { state } = flow
  const inputRef = useRef(null)
  const chooseRef = useRef(null)
  const [isDragging, setIsDragging] = useState(false)
  const busy = state.phase !== 'idle'

  return (
    <div className="page-shell">
      <Header />
      <main className="upload-page" aria-labelledby="upload-title">
        <h1 id="upload-title">보험약관 분석하기</h1>
        <img className="upload-page__illustration" src={documentImage} alt="여러 장 쌓인 보험 서류와 보안 방패" width="718" height="640" />
        <section className="upload-panel" aria-labelledby="upload-panel-title">
          <h2 id="upload-panel-title">약관을 업로드하고 분석을 받아보세요</h2>
          <input ref={inputRef} className="upload-file-input" type="file" accept=".pdf,application/pdf" aria-label="보험약관 PDF 파일 선택" disabled={busy} onChange={(event) => {
            if (event.currentTarget.files.length) flow.selectFiles(Array.from(event.currentTarget.files))
            event.currentTarget.value = ''
          }} />
          <div
            className={`upload-dropzone${isDragging ? ' upload-dropzone--dragging' : ''}`}
            aria-label="PDF 드래그 앤 드롭 영역"
            onDragOver={(event) => { event.preventDefault(); if (!busy) setIsDragging(true) }}
            onDragLeave={() => setIsDragging(false)}
            onDrop={(event) => { event.preventDefault(); setIsDragging(false); if (!busy) flow.selectFiles(Array.from(event.dataTransfer.files)) }}
          >
            <div className="upload-pdf-icon" aria-hidden="true">PDF</div>
            <p>PDF 파일을 여기에 끌어다 놓으세요</p>
            <p className="upload-dropzone__hint">또는 아래 버튼을 눌러 파일을 선택하세요</p>
            <button ref={chooseRef} type="button" className="workflow-button" disabled={busy} onClick={() => inputRef.current.click()}>파일 업로드 하기</button>
          </div>
          {state.file && <div className="upload-selected-file" aria-label="선택한 파일 정보">
            <dl><div><dt>파일 이름</dt><dd>{state.file.name}</dd></div><div><dt>파일 크기</dt><dd>{(state.file.size / 1024 / 1024).toLocaleString('ko-KR', { maximumFractionDigits: 2 })} MB ({state.file.size.toLocaleString('ko-KR')} bytes)</dd></div><div><dt>파일 형식</dt><dd>PDF ({state.file.type || 'application/pdf'})</dd></div></dl>
            <button type="button" className="workflow-link" disabled={busy} onClick={flow.close}>선택 파일 삭제</button>
          </div>}
          <div className="upload-panel__start"><button type="button" className="workflow-link" disabled={!state.file || busy} onClick={flow.startUpload}>업로드 시작하기 <span aria-hidden="true">›</span></button></div>
          {flow.preview && <div className="preview-tools"><p>미리보기 데이터 · 서버 요청 없이 화면을 테스트합니다.</p><button type="button" disabled={busy} onClick={flow.startSample}>샘플 약관으로 테스트</button></div>}
          <p className="upload-panel__limit">PDF 한 개 · 최대 {MAX_PDF_SIZE_MB}MB</p>
          <aside className="upload-privacy"><span aria-hidden="true">ⓘ</span><div><p>약관 파일은 보험사 홈페이지 상품공시 또는 가입 안내 메일에서 받을 수 있습니다.</p><p>업로드한 문서와 분석 결과는 임시 보관 후 자동 삭제됩니다. 보험증권과 이미지 스캔본은 분석할 수 없습니다.</p></div></aside>
          {state.notice && <p className="upload-panel__notice" role="status">{state.notice}</p>}
        </section>
      </main>
      {busy && <AnalysisModal title={titles[state.phase]} onClose={flow.close} variant={state.phase === 'error' ? 'error' : state.phase} returnFocusRef={chooseRef}>
        {state.phase === 'checking' && <DocumentChecking />}
        {state.phase === 'confirmation' && <DocumentConfirmation document={state.document} onNext={flow.confirmDocument} />}
        {state.phase === 'riders' && <RiderSelection document={state.document} riderIds={state.riderIds} viewAll={state.viewAll} isSubmitting={state.isSubmitting} onToggleRider={flow.toggleRider} onToggleViewAll={flow.toggleViewAll} onStart={flow.startAnalysis} />}
        {state.phase === 'progress' && <AnalysisProgress state={state} progress={flow.progress} onRetryStatus={flow.retryStatus} onRetryResult={flow.retryResult} onShowResults={flow.showResults} />}
        {state.phase === 'error' && <UploadErrorModal error={state.error} onRetry={flow.close} />}
        {flow.previewControls}
      </AnalysisModal>}
    </div>
  )
}

function LiveUploadPage() {
  const flow = useUploadFlow()
  return <UploadScreen flow={flow} />
}

function UploadPage() {
  const { UploadPreview } = usePreviewMode()
  return UploadPreview ? <UploadPreview /> : <LiveUploadPage />
}

export default UploadPage
