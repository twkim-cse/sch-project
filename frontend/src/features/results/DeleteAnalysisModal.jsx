import { useEffect, useRef, useState } from 'react'
import AnalysisModal from '../../components/AnalysisModal.jsx'
import './DeleteAnalysisModal.css'

function DeleteAnalysisModal({ preview = false, onClose, onDelete, onDeleted }) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const inFlight = useRef(false)
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  async function confirmDelete() {
    if (inFlight.current) return
    inFlight.current = true
    setPending(true)
    setError('')
    try {
      await onDelete()
      if (mounted.current) onDeleted()
    } catch (cause) {
      if (mounted.current) setError(cause.message || '삭제 요청을 완료하지 못했습니다. 다시 시도해 주세요.')
    } finally {
      inFlight.current = false
      if (mounted.current) setPending(false)
    }
  }

  return (
    <AnalysisModal title="분석 데이터를 삭제하시겠습니까?" className="delete-analysis-modal" onClose={onClose} closeDisabled={pending}>
      <p className="delete-analysis-modal__description">업로드한 보험약관과 분석 결과가 삭제됩니다. <span>삭제한 데이터는 복구할 수 없습니다.</span></p>
      {preview && <p className="delete-analysis-modal__preview">미리보기 데이터만 초기화합니다. 서버 삭제 요청은 전송하지 않습니다.</p>}
      {error && <p className="workflow-error" role="alert">{error}</p>}
      <button type="button" className="delete-analysis-modal__confirm" disabled={pending} onClick={confirmDelete}>{pending ? (preview ? '초기화 중…' : '삭제 요청 중…') : '삭제하기'}</button>
      {pending && <p role="status" className="delete-analysis-modal__status">{preview ? '미리보기 데이터를 초기화하고 있습니다.' : '서버의 삭제 완료 응답을 기다리고 있습니다.'}</p>}
    </AnalysisModal>
  )
}

export default DeleteAnalysisModal
