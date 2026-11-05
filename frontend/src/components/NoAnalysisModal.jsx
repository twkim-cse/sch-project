import { useId, useRef } from 'react'
import { useModalDialog } from './useModalDialog.js'
import './ActionButton.css'
import './NoAnalysisModal.css'
import { usePreviewMode } from '../features/preview/usePreviewMode.js'

// Frame 51 is isolated here so its visual design can be refined independently.
function NoAnalysisModal({ onClose, unavailable = false }) {
  const { Navigation } = usePreviewMode()
  const dialogRef = useRef(null)
  const closeButtonRef = useRef(null)
  const titleId = useId()
  const descriptionId = useId()

  useModalDialog(dialogRef, closeButtonRef)

  return (
    <dialog
      ref={dialogRef}
      className="no-analysis-modal"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={`${descriptionId} ${descriptionId}-help`}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
    >
      <div className="no-analysis-modal__content">
        <h2 id={titleId}>
          {unavailable ? '분석 결과를 확인할 수 없습니다' : '결과를 확인할 수 없습니다'}
        </h2>
        <p id={descriptionId} className="no-analysis-modal__description">
          {unavailable
            ? '잠시 후 다시 시도해 주세요.'
            : '아직 분석한 약관이 없습니다'}
        </p>
        <p id={`${descriptionId}-help`} className="no-analysis-modal__help">
          {unavailable ? '연결 상태를 확인한 후 다시 시도해 주세요.' : '먼저 약관을 업로드해 분석 후 다시 시도해 보세요'}
        </p>
        <button
          ref={closeButtonRef}
          type="button"
          className="no-analysis-modal__close"
          onClick={onClose}
        >
          닫기
        </button>
        {Navigation && <Navigation />}
      </div>
    </dialog>
  )
}

export default NoAnalysisModal
