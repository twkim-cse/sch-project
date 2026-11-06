function UploadErrorModal({ error, onRetry }) {
  return (
    <div className="upload-error">
      <p className="upload-error__description">업로드한 파일을 분석할 수 없습니다</p>
      <p className="upload-error__guide">PDF 파일을 확인한 후 다시 시도해 주세요</p>
      <p className="upload-error__instruction" role="alert">{error.instruction}</p>
      {error.message && error.message !== error.instruction && <p className="upload-error__server">{error.message}</p>}
      {error.deletionNotice && <p className="upload-error__server">{error.deletionNotice}</p>}
      <p className="upload-error__code">오류 코드: {error.code}</p>
      <button type="button" className="workflow-link" onClick={onRetry}>다시 시도하기</button>
    </div>
  )
}

export default UploadErrorModal
