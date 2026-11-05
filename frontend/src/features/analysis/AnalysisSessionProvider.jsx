import { useCallback, useMemo, useState } from 'react'
import { springApi } from '../../api/springApi.js'
import { createAnalysisService, forgetAnalysisReference, readAnalysisReference, rememberAnalysisId } from '../../services/analysisService.js'
import { createSpringAnalysisBackend } from '../../services/springAnalysisBackend.js'
import { AnalysisSessionContext } from './AnalysisSessionContext.js'

function AnalysisSessionProvider({ children, api = springApi, analysisService, persistReferences = true }) {
  const [reference, setReference] = useState(() => persistReferences ? readAnalysisReference() : null)
  const [completed, setCompleted] = useState(null)
  const service = useMemo(() => analysisService || createAnalysisService({
    backend: createSpringAnalysisBackend(api, () => reference),
    getCurrentAnalysisId: () => reference?.analysisId,
  }), [api, analysisService, reference])

  const rememberAnalysis = useCallback((analysisId, document) => {
    const next = { analysisId, documentId: document.documentId, expiresAt: document.expiresAt ?? null }
    setReference(next)
    if (persistReferences) rememberAnalysisId(analysisId, next)
  }, [persistReferences])

  const acceptCompleted = setCompleted

  const forgetAnalysis = useCallback((analysisId, documentId) => {
    if (persistReferences) forgetAnalysisReference(analysisId, documentId)
    const matches = (value) => value?.analysisId === analysisId || value?.documentId === documentId
    setReference((current) => matches(current) ? null : current)
    setCompleted((current) => matches(current?.analysis) ? null : current)
  }, [persistReferences])

  const value = useMemo(() => ({
    api, completed, service, rememberAnalysis, acceptCompleted, forgetAnalysis,
  }), [api, completed, service, rememberAnalysis, acceptCompleted, forgetAnalysis])

  return <AnalysisSessionContext.Provider value={value}>{children}</AnalysisSessionContext.Provider>
}

export default AnalysisSessionProvider
