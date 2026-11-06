import { MAX_PDF_SIZE_BYTES, MAX_PDF_SIZE_MB } from '../../config/analysis.js'
import { ApiError } from '../../api/springApi.js'

export function validatePdfSelection(files) {
  if (files.length !== 1) return { code: 'FILE_COUNT', message: 'PDF 파일은 한 번에 한 개만 선택해 주세요.' }
  const [file] = files
  if (!/\.pdf$/i.test(file.name) || !['', 'application/pdf', 'application/x-pdf', 'application/octet-stream'].includes(file.type)) {
    return { code: 'NOT_PDF' }
  }
  if (file.size > MAX_PDF_SIZE_BYTES) return { code: 'FILE_TOO_LARGE' }
  if (file.size === 0) return { code: 'EMPTY_FILE', message: '빈 파일은 업로드할 수 없습니다. PDF 파일을 다시 확인해 주세요.' }
  return null
}

export function getUploadError(error) {
  const source = error?.reject || error?.error || error || {}
  const code = typeof source.code === 'string' ? source.code : 'UNKNOWN_ERROR'
  const instructions = {
    NOT_PDF: 'PDF 파일만 업로드할 수 있습니다.',
    FILE_TOO_LARGE: `파일 용량이 초과되었습니다. ${MAX_PDF_SIZE_MB}MB 이하의 PDF 파일을 업로드해 주세요.`,
    NO_TEXT: 'PDF에서 글자를 추출할 수 없습니다. 보험사 홈페이지에서 원본 약관을 다시 받아 주세요.',
    NOT_TERMS: '보험약관이 아닌 문서입니다. 보험약관 PDF를 업로드해 주세요.',
    POLICY_DETECTED: '보험증권으로 확인된 문서입니다. 보험약관 파일을 업로드해 주세요.',
    STRUCTURE_FAILED: '문서의 조항 구조를 인식하지 못했습니다. 해당 약관은 현재 지원되지 않습니다.',
    NOT_FOUND: '문서를 찾을 수 없거나 보관 기간이 지났습니다. 약관을 다시 업로드해 주세요.',
    NOT_READY: '서버에서 아직 작업을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
    ANALYSIS_FAILED: '분석을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.',
  }
  const details = error?.details || error
  const reported = details?.reject || details?.error || details
  const deletion = source.deleted ?? source.fileDeleted ?? source.documentDeleted
    ?? reported?.deleted ?? reported?.fileDeleted ?? reported?.documentDeleted
    ?? details?.deleted ?? details?.fileDeleted ?? details?.documentDeleted
  return {
    code,
    message: typeof source.message === 'string' ? source.message : '',
    instruction: instructions[code] || (typeof source.message === 'string' ? source.message : '') || '요청을 처리하지 못했습니다. 연결 상태와 파일을 확인한 후 다시 시도해 주세요.',
    deletionNotice: code !== 'POLICY_DETECTED' ? '' : deletion === true
      ? '서버에서 해당 파일을 삭제했다고 응답했습니다.'
      : deletion === false
        ? '서버에서 해당 파일이 아직 삭제되지 않았다고 응답했습니다.'
        : '서버의 파일 삭제 여부는 확인되지 않았습니다.',
  }
}

// Validate response types without coercing values or changing the payload.
const isObject = (value) => value !== null && typeof value === 'object' && !Array.isArray(value)
const isString = (value) => typeof value === 'string'
const isId = (value) => isString(value) && value.length > 0
const isNumber = (value) => typeof value === 'number' && Number.isFinite(value)
const nullable = (check) => (value) => value == null || check(value)
const hasFields = (value, fields) => fields.every((field) => Object.hasOwn(value, field))
const isError = (value) => isObject(value) && isString(value.code) && isString(value.message)
const isMetadata = (value) => isObject(value)
  && hasFields(value, ['productName', 'versionLabel', 'pageCount'])
  && nullable(isString)(value.productName) && nullable(isString)(value.versionLabel)
  && nullable(isNumber)(value.pageCount)
const isSource = (value) => isObject(value)
  && ['section', 'article', 'paragraph'].every((field) => nullable(isString)(value[field]))
  && isNumber(value.page)

export function assertDocument(data, expectedId) {
  if (!isObject(data) || !isId(data.documentId) || (expectedId && data.documentId !== expectedId) || !['CHECKING', 'READY', 'REJECTED'].includes(data.status)) {
    throw new ApiError('INVALID_RESPONSE', '문서 확인 응답 형식을 확인할 수 없습니다.')
  }
  if (data.status === 'READY') {
    // Preserve the existing unknown-metadata/retention UI for null values.
    // CHECKING and REJECTED do not require READY-only fields.
    if (!isMetadata(data) || !hasFields(data, ['riders', 'expiresAt'])) {
      throw new ApiError('INVALID_RESPONSE', '문서 정보 응답 형식을 확인할 수 없습니다.')
    }
    if (data.riders != null && (!Array.isArray(data.riders) || data.riders.some((rider) => !isObject(rider)
      || !isId(rider.riderId) || !isString(rider.name) || !isNumber(rider.pageStart)))) {
      throw new ApiError('INVALID_RESPONSE', '서버의 특약 목록 형식을 확인할 수 없습니다.')
    }
    if (data.expiresAt != null && (!isString(data.expiresAt) || !Number.isFinite(Date.parse(data.expiresAt)))) {
      throw new ApiError('INVALID_RESPONSE', '문서 보관 만료 시각의 형식을 확인할 수 없습니다.')
    }
  }
  if ((data.status === 'REJECTED' || data.reject != null) && !isError(data.reject)) {
    throw new ApiError('INVALID_RESPONSE', '문서 거절 응답 형식을 확인할 수 없습니다.')
  }
  return data
}

export function assertAnalysis(data, expectedId) {
  if (!isObject(data) || !isId(data.analysisId) || (expectedId && data.analysisId !== expectedId) || !['QUEUED', 'RUNNING', 'DONE', 'FAILED'].includes(data.status)) {
    throw new ApiError('INVALID_RESPONSE', '분석 상태 응답 형식을 확인할 수 없습니다.')
  }
  const running = data.status === 'RUNNING'
  if ((running || data.step != null) && !steps.some(([step]) => step === data.step)
    || ['stepIndex', 'stepCount'].some((field) => (running || Object.hasOwn(data, field)) && !isNumber(data[field]))) {
    throw new ApiError('INVALID_RESPONSE', '분석 진행 단계 응답 형식을 확인할 수 없습니다.')
  }
  if ((data.status === 'FAILED' || data.error != null) && !isError(data.error)) {
    throw new ApiError('INVALID_RESPONSE', '분석 오류 응답 형식을 확인할 수 없습니다.')
  }
  return data
}

export function assertResult(data, expectedId) {
  if (!isObject(data) || !isId(data.analysisId) || data.analysisId !== expectedId || !isMetadata(data.document)
    || !Array.isArray(data.groups) || !Array.isArray(data.checkSummary)) {
    throw new ApiError('INVALID_RESPONSE', '분석 결과 형식을 확인할 수 없습니다. 결과를 다시 불러와 주세요.')
  }
  if (typeof data.viewAll !== 'boolean' || !Array.isArray(data.selectedRiders)
    || data.selectedRiders.some((rider) => !isObject(rider) || !isId(rider.riderId) || !isString(rider.name))
    || data.groups.some((group) => !isObject(group) || !['MAIN', 'RIDER'].includes(group.scope)
      || (group.scope === 'MAIN' ? group.riderId !== null : !isId(group.riderId))
      || !isString(group.name) || !Array.isArray(group.cards) || group.cards.some((card) => !isObject(card)
        || !isId(card.cardId) || !isId(card.clauseId)
        || !Number.isInteger(card.checkItem) || card.checkItem < 1 || card.checkItem > 6
        || !['HIGH', 'NORMAL'].includes(card.importance) || !['FULL', 'QUOTE_ONLY'].includes(card.display)
        || !isString(card.quote) || typeof card.fromTable !== 'boolean' || !isSource(card.source)
        || !hasFields(card, ['title', 'explanation'])
        || (card.display === 'FULL'
          ? !isString(card.title) || !isString(card.explanation)
          : !nullable(isString)(card.title) || !nullable(isString)(card.explanation))))) {
    throw new ApiError('INVALID_RESPONSE', '분석 결과 카드 형식을 확인할 수 없습니다.')
  }
  const items = data.checkSummary.map((item) => item?.checkItem)
  if (new Set(items).size !== 6 || items.length !== 6 || items.some((item) => !Number.isInteger(item) || item < 1 || item > 6)
    || data.checkSummary.some((item) => !isObject(item) || !['FOUND', 'NOT_FOUND'].includes(item.status)
      || !isString(item.name) || !isNumber(item.cardCount))) {
    throw new ApiError('INVALID_RESPONSE', '6개 점검 항목의 결과 요약을 확인할 수 없습니다.')
  }
  return data
}

const steps = [
  ['EXTRACTING', '주요 조항 추출'],
  ['CHECKING_QUOTES', '원문 인용 대조'],
  ['EXPLAINING', '쉬운 말 해설 생성'],
  ['FINALIZING', '최종 검증 및 정리'],
]

export function getAnalysisProgress(analysis) {
  const done = analysis?.status === 'DONE'
  const activeIndex = ['RUNNING', 'FAILED'].includes(analysis?.status) ? steps.findIndex(([step]) => step === analysis.step) : -1
  const estimated = Number.isFinite(analysis?.stepIndex) && Number.isFinite(analysis?.stepCount) && analysis.stepCount > 0
    ? Math.max(0, Math.min(99, Math.round(analysis.stepIndex / analysis.stepCount * 100)))
    : 0
  return {
    percent: done ? 100 : analysis?.status === 'QUEUED' ? 0 : estimated,
    stages: [
      { name: '약관 텍스트 추출', state: 'done' },
      ...steps.map(([step, name], index) => ({
        step, name,
        state: done || (activeIndex >= 0 && index < activeIndex) ? 'done'
          : analysis?.status === 'FAILED' && analysis.step === step ? 'failed'
            : activeIndex === index ? 'running' : 'waiting',
      })),
    ],
  }
}

export function displayValue(value) {
  return value == null || value === '' ? '확인되지 않음' : String(value)
}

export function formatExpiry(value) {
  if (!value || !Number.isFinite(Date.parse(value))) return '확인되지 않음'
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Seoul' }).format(new Date(value))
}
