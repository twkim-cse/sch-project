import { useMemo, useState, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import NoAnalysisModal from '../../components/NoAnalysisModal.jsx'
import { PreviewModeContext } from './PreviewModeContext.js'
import { createMockAnalysisSession } from './mockAnalysisSession.js'
import MockAnalysisFlow from './MockAnalysisFlow.jsx'
import PreviewNavigation from './PreviewNavigation.jsx'
import './PreviewMode.css'

function EmptyResultPreview() {
  const [closed, setClosed] = useState(false)
  return !closed && <NoAnalysisModal onClose={() => setClosed(true)} />
}

function PreviewModeProvider({ Application }) {
  const [controller] = useState(createMockAnalysisSession)
  const snapshot = useSyncExternalStore(controller.subscribe, controller.getSnapshot)
  const location = useLocation()
  const params = new URLSearchParams(location.search)
  const frame = params.get('previewFrame')
  const errorCode = params.get('previewError') || 'NOT_PDF'
  const value = useMemo(() => ({
    active: true, controller, frame, errorCode,
    analysisId: snapshot.bundle?.analysis.analysisId || null,
    UploadPreview: MockAnalysisFlow, Navigation: PreviewNavigation,
  }), [controller, snapshot, frame, errorCode])

  return (
    <PreviewModeContext.Provider value={value}>
      <Application api={controller.api} analysisService={controller.service} persistReferences={false}>
        <PreviewNavigation global />
        {frame === '51' && <EmptyResultPreview />}
      </Application>
    </PreviewModeContext.Provider>
  )
}

export default PreviewModeProvider
