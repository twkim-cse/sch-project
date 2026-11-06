import { displayValue, formatExpiry } from './uploadModel.js'

function DocumentConfirmation({ document, onNext }) {
  return (
    <>
      <p className="analysis-modal__subtitle">업로드한 보험약관의 정보를 확인해 주세요</p>
      <div className="analysis-modal__panel document-confirmation" aria-label="확인된 보험약관 정보">
        <dl>
          <div><dt>보험 상품명</dt><dd>{displayValue(document.productName)}</dd></div>
          <div><dt>약관 개정 시기</dt><dd>{displayValue(document.versionLabel)}</dd></div>
          <div><dt>약관 전체 페이지 수</dt><dd>{document.pageCount == null ? '확인되지 않음' : `${document.pageCount}쪽`}</dd></div>
          <div><dt>문서 보관 만료 시각</dt><dd>{formatExpiry(document.expiresAt)}</dd></div>
        </dl>
        <h3>확인된 특약 목록</h3>
        {document.riders == null ? <p>확인되지 않음</p> : document.riders.length === 0 ? <p>확인된 특약이 없습니다.</p> : (
          <ul>{document.riders.map((rider) => <li key={rider.riderId}>{displayValue(rider.name)}{rider.pageStart != null && ` · ${rider.pageStart}쪽부터`}</li>)}</ul>
        )}
      </div>
      <p className="document-confirmation__notice">확인된 보험약관이 실제 가입한 상품과 일치하는지 확인해 주세요.</p>
      <div className="analysis-modal__action document-confirmation__action">
        <button type="button" className="workflow-button" onClick={onNext}>확인하고 다음으로</button>
      </div>
    </>
  )
}

export default DocumentConfirmation
