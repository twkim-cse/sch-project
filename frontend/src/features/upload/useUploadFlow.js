import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ANALYSIS_POLL_INTERVAL_MS, DOCUMENT_POLL_INTERVAL_MS } from '../../config/analysis.js'
import { ApiError } from '../../api/springApi.js'
import { paths } from '../../routes/paths.js'
import { useAnalysisSession } from '../analysis/useAnalysisSession.js'
import { assertAnalysis, assertDocument, assertResult, getUploadError, validatePdfSelection } from './uploadModel.js'

const emptyState = {
  phase: 'idle', file: null, document: null, riderIds: [], viewAll: false,
  analysis: null, result: null, error: null, pollError: null, resultError: null,
  isSubmitting: false, isResultLoading: false, notice: '',
}

export function useUploadFlow() {
  const { api, rememberAnalysis, acceptCompleted } = useAnalysisSession()
  const navigate = useNavigate()
  const [state, setState] = useState(emptyState)
  const operation = useRef({ version: 0, timer: null, controller: null })
  const submitting = useRef(false)

  const stop = useCallback(() => {
    operation.current.version += 1
    clearTimeout(operation.current.timer)
    operation.current.controller?.abort()
    operation.current.timer = null
    operation.current.controller = null
    submitting.current = false
  }, [])
  useEffect(() => stop, [stop])

  function begin() {
    stop()
    const controller = new AbortController()
    operation.current.controller = controller
    const version = operation.current.version
    return { signal: controller.signal, active: () => operation.current.version === version && !controller.signal.aborted }
  }

  function schedule(callback, op, interval) {
    if (op.active()) operation.current.timer = setTimeout(callback, interval)
  }

  function fail(error, op) {
    if (!op.active() || error.name === 'AbortError') return
    stop()
    setState((previous) => ({ ...previous, phase: 'error', error: getUploadError(error), isSubmitting: false, isResultLoading: false }))
  }

  function selectFiles(files) {
    if (state.phase !== 'idle') return
    const error = validatePdfSelection(Array.from(files))
    if (error) {
      stop()
      setState({ ...emptyState, phase: 'error', error: getUploadError(error) })
      return
    }
    setState({ ...emptyState, file: files[0] })
  }

  function close() {
    stop()
    setState({ ...emptyState, notice: state.analysis?.analysisId
      ? '분석 화면을 닫았습니다. 서버에서 이미 시작된 분석은 계속될 수 있습니다.' : '' })
  }

  async function queryDocument(documentId, op) {
    try {
      const document = assertDocument(await api.getDocument(documentId, { signal: op.signal }), documentId)
      if (!op.active()) return
      if (document.status === 'REJECTED') { fail(document, op); return }
      if (document.status === 'READY') {
        setState((previous) => ({ ...previous, phase: 'confirmation', document, isSubmitting: false }))
        return
      }
      setState((previous) => ({ ...previous, document }))
      schedule(() => queryDocument(documentId, op), op, DOCUMENT_POLL_INTERVAL_MS)
    } catch (error) { fail(error, op) }
  }

  async function startUpload() {
    if (state.phase !== 'idle' || !state.file || submitting.current) return
    const op = begin()
    submitting.current = true
    setState((previous) => ({ ...previous, phase: 'checking', isSubmitting: true, error: null }))
    try {
      const document = assertDocument(await api.uploadDocument(state.file, { signal: op.signal }))
      if (!op.active()) return
      if (document.status === 'REJECTED') { fail(document, op); return }
      setState((previous) => ({ ...previous, document }))
      await queryDocument(document.documentId, op)
    } catch (error) { fail(error, op) }
    finally { if (op.active()) submitting.current = false }
  }

  function confirmDocument() {
    if (state.document?.status !== 'READY') return
    if (state.document.expiresAt && Date.parse(state.document.expiresAt) <= Date.now()) {
      setState((previous) => ({ ...previous, phase: 'error', error: getUploadError({ code: 'NOT_FOUND' }) }))
      return
    }
    setState((previous) => ({ ...previous, phase: 'riders' }))
  }

  function toggleRider(riderId) {
    if (state.viewAll || state.isSubmitting) return
    setState((previous) => ({ ...previous, riderIds: previous.riderIds.includes(riderId)
      ? previous.riderIds.filter((id) => id !== riderId) : [...previous.riderIds, riderId] }))
  }

  function toggleViewAll() {
    if (!state.isSubmitting) setState((previous) => ({ ...previous, viewAll: !previous.viewAll }))
  }

  async function loadResult(analysis, document, op) {
    setState((previous) => ({ ...previous, isResultLoading: true, resultError: null }))
    try {
      const result = assertResult(await api.getResult(analysis.analysisId, { signal: op.signal }), analysis.analysisId)
      if (!op.active()) return
      if (document.expiresAt && Date.parse(document.expiresAt) <= Date.now()) {
        throw new ApiError('NOT_FOUND', '문서의 보관 기간이 지났습니다. 약관을 다시 업로드해 주세요.')
      }
      acceptCompleted({ analysis: { ...analysis, documentId: document.documentId, document, expiresAt: document.expiresAt }, result })
      setState((previous) => ({ ...previous, result, isResultLoading: false }))
    } catch (error) {
      if (op.active() && error.name !== 'AbortError') setState((previous) => ({ ...previous, isResultLoading: false, resultError: getUploadError(error) }))
    }
  }

  async function queryAnalysis(analysisId, document, op) {
    try {
      const analysis = assertAnalysis(await api.getAnalysis(analysisId, { signal: op.signal }), analysisId)
      if (!op.active()) return
      setState((previous) => ({ ...previous, analysis: { ...previous.analysis, ...analysis }, pollError: null }))
      if (analysis.status === 'FAILED') return
      if (analysis.status === 'DONE') { await loadResult(analysis, document, op); return }
      schedule(() => queryAnalysis(analysisId, document, op), op, ANALYSIS_POLL_INTERVAL_MS)
    } catch (error) {
      if (op.active() && error.name !== 'AbortError') {
        stop()
        setState((previous) => ({ ...previous, pollError: getUploadError(error) }))
      }
    }
  }

  async function startAnalysis() {
    if (state.phase !== 'riders' || state.isSubmitting || submitting.current) return
    const op = begin()
    submitting.current = true
    setState((previous) => ({ ...previous, isSubmitting: true }))
    try {
      const analysis = assertAnalysis(await api.startAnalysis({
        documentId: state.document.documentId,
        riderIds: state.viewAll ? [] : state.riderIds,
        viewAll: state.viewAll,
      }, { signal: op.signal }))
      if (!op.active()) return
      rememberAnalysis(analysis.analysisId, state.document)
      setState((previous) => ({ ...previous, phase: 'progress', analysis, isSubmitting: false }))
      await queryAnalysis(analysis.analysisId, state.document, op)
    } catch (error) { fail(error, op) }
    finally { if (op.active()) submitting.current = false }
  }

  function retryStatus() {
    if (!state.analysis?.analysisId) return
    const op = begin()
    setState((previous) => ({ ...previous, pollError: null }))
    void queryAnalysis(state.analysis.analysisId, state.document, op)
  }

  function retryResult() {
    if (state.analysis?.status !== 'DONE' || state.isResultLoading) return
    const op = begin()
    void loadResult(state.analysis, state.document, op)
  }

  function showResults() {
    if (state.analysis?.status !== 'DONE' || !state.result) return
    navigate(paths.results, { state: { analysisId: state.analysis.analysisId, documentId: state.document.documentId } })
  }

  return { state, selectFiles, close, startUpload, confirmDocument, toggleRider, toggleViewAll, startAnalysis, retryStatus, retryResult, showResults }
}
