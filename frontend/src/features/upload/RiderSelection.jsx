import checkIcon from '../../assets/미니멀 파란 체크박스 아이콘.png'
import { displayValue } from './uploadModel.js'

function RiderSelection({ document, riderIds, viewAll, isSubmitting, onToggleRider, onToggleViewAll, onStart }) {
  const riders = document.riders || []
  return (
    <>
      <p className="analysis-modal__subtitle rider-selection__subtitle">
        분석할 특약을 선택해 주세요<br />가입한 특약을 모르신다면 전체 보기를 선택하세요
      </p>
      <div className="main-contract" aria-label="주계약 항상 포함">
        <img src={checkIcon} alt="" width="149" height="99" />
        <div><h3>주계약 (기본 보장)</h3><p>항상 분석에 포함됩니다</p></div>
        <span>필수 포함</span>
      </div>
      <div className="rider-selection">
        <div className="rider-selection__heading"><h3>가입한 특약</h3><p aria-live="polite">선택한 특약 {viewAll ? riders.length : riderIds.length}개{viewAll ? ' · 전체 보기' : ''}</p></div>
        <div className="rider-selection__list" aria-label="가입한 특약 목록">
          {riders.map((rider) => (
            <label className="rider-row" key={rider.riderId}>
              <input type="checkbox" checked={riderIds.includes(rider.riderId)} disabled={viewAll || isSubmitting} onChange={() => onToggleRider(rider.riderId)} />
              <span>{displayValue(rider.name)}</span>
              {rider.pageStart != null && <small>{rider.pageStart}쪽</small>}
            </label>
          ))}
          {riders.length === 0 && <p>{document.riders == null ? '특약 정보가 확인되지 않았습니다. 주계약은 항상 포함됩니다.' : '확인된 특약이 없습니다. 주계약을 분석합니다.'}</p>}
        </div>
        <label className="rider-row rider-row--all">
          <input type="checkbox" checked={viewAll} disabled={isSubmitting} onChange={onToggleViewAll} />
          <span>전체 보기</span>
        </label>
        <div className="analysis-modal__action">
          <button type="button" className="workflow-button" disabled={isSubmitting} aria-busy={isSubmitting} onClick={onStart}>
            {isSubmitting ? '분석 요청 중 ...' : '분석 시작하기'}
          </button>
        </div>
      </div>
    </>
  )
}

export default RiderSelection
