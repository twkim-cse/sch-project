import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/springApi.js'
import { assertAnalysis, assertDocument, assertResult, getAnalysisProgress } from '../features/upload/uploadModel.js'
import { assertClause } from '../features/results/resultModel.js'
import { completedResult, readyDocument } from './analysisFixtures.js'

const running = { analysisId: 'test-analysis', status: 'RUNNING', step: 'EXPLAINING', stepIndex: 3, stepCount: 4 }
const clause = { clauseId: 'test-clause-1', section: '주계약', article: '제12조', paragraph: '제2항', page: 14, articleTitle: '조항 제목', text: '원문\n 인용' }
function changed(original, path, value) {
  const copy = structuredClone(original)
  const fields = path.split('.')
  const field = fields.pop()
  const target = fields.reduce((item, key) => item[key], copy)
  if (value === undefined) delete target[field]
  else target[field] = value
  return copy
}
function expectInvalid(validate) {
  expect(validate).toThrow(ApiError)
  expect(validate).toThrow(expect.objectContaining({ code: 'INVALID_RESPONSE' }))
}

describe('document response types', () => {
  it('preserves valid metadata, null values and empty rider lists without coercion', () => {
    for (const document of [readyDocument,
      { ...readyDocument, productName: null, versionLabel: null, riders: [] },
      { ...readyDocument, productName: null, versionLabel: null, pageCount: null, riders: null, expiresAt: null },
      { documentId: 'test-document', status: 'CHECKING' },
      { documentId: 'test-document', status: 'REJECTED', reject: { code: 'NO_TEXT', message: '글자 없음' } },
    ]) {
      const before = structuredClone(document)
      expect(assertDocument(document, 'test-document')).toBe(document)
      expect(document).toEqual(before)
    }
  })
  it.each([
    ['documentId', 42], ['documentId', undefined], ['status', undefined],
    ['productName', {}], ['versionLabel', []], ['productName', undefined],
    ['pageCount', '212'], ['pageCount', undefined], ['expiresAt', 123], ['expiresAt', 'bad-date'],
    ['riders', {}], ['riders', undefined], ['riders.0', null], ['riders.0', []],
    ['riders.0.riderId', 1], ['riders.0.name', {}], ['riders.0.name', undefined],
    ['riders.0.pageStart', { value: 85 }], ['riders.0.pageStart', '85'],
    ['riders.0.pageStart', undefined], ['riders.0.pageStart', Number.NaN],
  ])('rejects invalid %s (%#)', (path, value) => {
    const data = changed(readyDocument, path, value)
    const before = structuredClone(data)
    expectInvalid(() => assertDocument(data, 'test-document'))
    expect(data).toEqual(before)
  })
  it.each([undefined, [], { code: 'NO_TEXT' }, { code: {}, message: '오류' }, { code: 'NO_TEXT', message: {} }])('rejects malformed rejection %#', (reject) => {
    expectInvalid(() => assertDocument({ documentId: 'test-document', status: 'REJECTED', reject }))
  })
})

describe('analysis response types', () => {
  it('accepts the four steps, minimal queued/done responses and failure without progress', () => {
    for (const step of ['EXTRACTING', 'CHECKING_QUOTES', 'EXPLAINING', 'FINALIZING']) {
      const data = { ...running, step }
      expect(assertAnalysis(data, data.analysisId)).toBe(data)
    }
    for (const status of ['QUEUED', 'DONE']) {
      const data = { analysisId: 'test-analysis', status }
      expect(assertAnalysis(data, data.analysisId)).toBe(data)
    }
    const failed = { analysisId: 'test-analysis', status: 'FAILED', error: { code: 'ANALYSIS_FAILED', message: '다시 시도' } }
    expect(assertAnalysis(failed, failed.analysisId)).toBe(failed)
    expect(getAnalysisProgress(assertAnalysis(running, running.analysisId)).percent).toBe(75)
  })
  it.each([
    ['analysisId', {}], ['analysisId', undefined], ['step', 'UNKNOWN'], ['step', {}], ['step', undefined],
    ['stepIndex', '3'], ['stepIndex', {}], ['stepIndex', null], ['stepIndex', undefined], ['stepIndex', Infinity],
    ['stepCount', '4'], ['stepCount', null], ['stepCount', undefined],
  ])('rejects invalid %s (%#)', (path, value) => {
    const data = changed(running, path, value)
    const before = structuredClone(data)
    expectInvalid(() => assertAnalysis(data, running.analysisId))
    expect(data).toEqual(before)
  })
  it.each([undefined, [], { code: 'ANALYSIS_FAILED', message: {} }])('rejects malformed failure %#', (error) => {
    expectInvalid(() => assertAnalysis({ analysisId: 'test-analysis', status: 'FAILED', error }))
  })
})

describe('result response types', () => {
  it('accepts nullable document metadata, MAIN riderId and QUOTE_ONLY text', () => {
    const result = structuredClone(completedResult)
    result.document.productName = null
    result.document.versionLabel = null
    result.groups[1].cards[0].title = null
    result.groups[1].cards[0].explanation = null
    result.groups[1].cards[0].source.paragraph = null
    const before = structuredClone(result)
    expect(assertResult(result, result.analysisId)).toBe(result)
    expect(result).toEqual(before)
    expect(result.groups[0].riderId).toBeNull()
    expect(result.groups[0].cards[0].fromTable).toBe(false)
    expect(result.groups[1].cards[0].fromTable).toBe(true)
  })
  it('accepts empty selections/groups with all six not-found summaries', () => {
    const result = { ...completedResult, selectedRiders: [], groups: [], checkSummary: completedResult.checkSummary.map((item) => ({ ...item, status: 'NOT_FOUND', cardCount: 0 })) }
    expect(assertResult(result, result.analysisId)).toBe(result)
  })
  it.each([
    ['analysisId', undefined], ['document', []], ['document.productName', {}], ['document.pageCount', '212'],
    ['viewAll', 'false'], ['selectedRiders', {}], ['selectedRiders.0', []], ['selectedRiders.0.riderId', 1],
    ['selectedRiders.0.name', {}], ['groups', null], ['groups.0', []], ['groups.0.scope', 'OTHER'],
    ['groups.0.riderId', {}], ['groups.1.riderId', null], ['groups.0.name', {}], ['groups.0.cards', {}],
    ['groups.0.cards.0', []], ['groups.0.cards.0.cardId', undefined], ['groups.0.cards.0.clauseId', {}],
    ['groups.0.cards.0.fromTable', 'false'], ['groups.0.cards.0.fromTable', undefined], ['groups.0.cards.0.fromTable', null],
    ['groups.0.cards.0.source', undefined], ['groups.0.cards.0.source', []], ['groups.0.cards.0.source.page', '14'],
    ['groups.0.cards.0.source.section', {}], ['groups.0.cards.0.source.paragraph', []],
    ['groups.0.cards.0.title', null], ['groups.0.cards.0.explanation', {}], ['groups.1.cards.0.title', {}],
    ['checkSummary', {}], ['checkSummary.0', null], ['checkSummary.0.cardCount', '1'], ['checkSummary.0.cardCount', undefined],
  ])('rejects invalid %s (%#)', (path, value) => {
    const data = changed(completedResult, path, value)
    const before = structuredClone(data)
    expectInvalid(() => assertResult(data, completedResult.analysisId))
    expect(data).toEqual(before)
  })
})

describe('clause response types', () => {
  it('preserves the full original text and optional source-label fallbacks', () => {
    for (const data of [clause, { clauseId: clause.clauseId, text: clause.text },
      { ...clause, section: null, article: null, paragraph: null, articleTitle: null, page: null }]) {
      const before = structuredClone(data)
      expect(assertClause(data, clause.clauseId)).toBe(data)
      expect(data).toEqual(before)
    }
  })
  it.each([
    ['clauseId', undefined], ['clauseId', 1], ['clauseId', 'wrong'], ['text', {}], ['text', undefined],
    ['articleTitle', { value: 'title' }], ['section', []], ['article', {}], ['paragraph', 1], ['page', '14'],
  ])('rejects invalid %s (%#)', (path, value) => {
    expectInvalid(() => assertClause(changed(clause, path, value), clause.clauseId))
  })
})