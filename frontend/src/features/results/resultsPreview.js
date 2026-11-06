// Shared sample data for the development-only preview provider.
// These fictional examples are never stored as a completed server analysis.
import { ApiError } from '../../api/springApi.js'

export const previewDocument = {
  documentId: 'preview-document', status: 'READY',
  productName: '(예시) 종합건강보험', versionLabel: '2025년 4월', pageCount: 212, expiresAt: null,
  riders: ['암진단 특별약관', '질병입원 특별약관', '수술비 특별약관', '상해보장 특별약관'].map((name, index) => ({ riderId: `preview-rider-${index + 1}`, name, pageStart: 70 + index * 30 })),
}

const previewCheckItems = ['면책 사유', '대기 기간', '갱신 조건', '알릴 의무', '약관상 질병·수술의 정의', '중도 해지 및 해지환급금']

export const previewErrors = [
  ['NOT_PDF', 'PDF 형식 오류'], ['FILE_TOO_LARGE', '50MB 용량 초과'],
  ['NO_TEXT', '텍스트 추출 실패'], ['NOT_TERMS', '보험약관이 아닌 문서'],
]

export function createResultsPreview({ riderIds = ['preview-rider-1'], viewAll = false } = {}) {
  const documentId = 'preview-document'
  const analysisId = 'preview-analysis'
  const definitions = [
    {
      checkItem: 1, importance: 'HIGH', display: 'FULL', title: '보험금 지급 제한 조건',
      explanation: '보장되는 사고라도 약관에서 정한 예외에 해당하면 보험금이 지급되지 않을 수 있다는 뜻입니다. 아래 문구는 화면 확인을 위한 가상 예시입니다.',
      quote: '회사는 고의로 발생시킨 보험금 지급사유에 대해서는 보험금을 지급하지 않습니다.',
      source: { section: '주계약', article: '제12조', paragraph: '제2항', page: 14 },
    },
    {
      checkItem: 3, importance: 'HIGH', display: 'FULL', title: '갱신 시 보험료 변경',
      explanation: '갱신 시점에 보험료가 달라질 수 있다는 가상 예시입니다. 실제 보험료와 갱신 조건은 본인의 약관 원문을 확인해야 합니다.',
      quote: '갱신계약의 보험료는 갱신일 현재의 보험료율을 적용합니다.',
      source: { section: '주계약', article: '제20조', paragraph: '제1항', page: 26 },
    },
    {
      checkItem: 2, importance: 'NORMAL', display: 'QUOTE_ONLY', title: null, explanation: null,
      quote: '보장개시일은 계약일을 포함하여 90일이 지난 날의 다음 날로 합니다.',
      source: { section: '암진단 특별약관', article: '제5조', paragraph: '제1항', page: 74 }, fromTable: true,
    },
  ]
  const cards = definitions.map((card, index) => ({ ...card, cardId: `preview-card-${index + 1}`, clauseId: `preview-clause-${index + 1}`, fromTable: card.fromTable ?? false }))
  const clauses = new Map(cards.map((card) => [card.clauseId, {
    clauseId: card.clauseId, ...card.source, articleTitle: card.title || '보장개시일',
    text: `[미리보기용 가상 약관 — 실제 보험약관이 아닙니다]\n\n${card.source.article} ${card.title || '보장개시일'}\n${card.quote}\n\n${Array.from({ length: 22 }, (_, index) => `${index + 1}. 이 문단은 긴 원문의 내부 스크롤을 확인하기 위한 가상 문구입니다. 실제 보장 내용이나 보험금 지급 조건을 나타내지 않습니다.`).join('\n\n')}`,
  }]))
  const selectedRiders = previewDocument.riders.filter((rider) => viewAll || riderIds.includes(rider.riderId))
  const visibleCards = cards.filter((card, index) => index < 2 || selectedRiders.some((rider) => rider.riderId === 'preview-rider-1'))
  return {
    bundle: {
      analysis: { analysisId, documentId, document: previewDocument, status: 'DONE', expiresAt: null },
      result: {
        analysisId,
        document: { productName: previewDocument.productName, versionLabel: previewDocument.versionLabel, pageCount: previewDocument.pageCount },
        selectedRiders, viewAll,
        checkSummary: previewCheckItems.map((name, index) => {
          const cardCount = visibleCards.filter((card) => card.checkItem === index + 1).length
          return { checkItem: index + 1, name, status: cardCount ? 'FOUND' : 'NOT_FOUND', cardCount }
        }),
        groups: [
          { scope: 'MAIN', riderId: null, name: '공통 (주계약)', cards: cards.slice(0, 2) },
          ...selectedRiders.filter((rider) => rider.riderId === 'preview-rider-1').map((rider) => ({ scope: 'RIDER', riderId: rider.riderId, name: rider.name, cards: cards.slice(2) })),
        ],
      },
    },
    api: {
      async getClause(requestDocumentId, clauseId) {
        if (requestDocumentId !== documentId || !clauses.has(clauseId)) throw new ApiError('NOT_FOUND', '미리보기 원문을 찾지 못했습니다.')
        return clauses.get(clauseId)
      },
    },
  }
}
