import { useEffect, useId, useRef } from 'react'
import { useModalDialog } from './useModalDialog.js'
import './AnalysisModal.css'
import { usePreviewMode } from '../features/preview/usePreviewMode.js'

function AnalysisModal({ title, onClose, children, variant = 'workflow', returnFocusRef, className = '', closeDisabled = false }) {
  const { Navigation } = usePreviewMode()
  const dialogRef = useRef(null)
  const closeRef = useRef(null)
  const titleRef = useRef(null)
  const titleId = useId()
  useModalDialog(dialogRef, closeRef, returnFocusRef)

  useEffect(() => {
    titleRef.current?.focus({ preventScroll: true })
  }, [title])

  return (
    <dialog
      ref={dialogRef}
      className={`analysis-modal analysis-modal--${variant} ${className}`}
      aria-modal="true"
      aria-labelledby={titleId}
      onCancel={(event) => { event.preventDefault(); if (!closeDisabled) onClose() }}
    >
      <div className="analysis-modal__sheet">
        <button ref={closeRef} type="button" className="analysis-modal__close" disabled={closeDisabled} onClick={onClose}>닫기</button>
        <h2 ref={titleRef} id={titleId} className="analysis-modal__title" tabIndex={-1}>{title}</h2>
        {children}
        {Navigation && <Navigation />}
      </div>
    </dialog>
  )
}

export default AnalysisModal
