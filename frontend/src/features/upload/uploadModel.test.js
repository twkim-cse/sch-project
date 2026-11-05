import { describe, expect, it } from 'vitest'
import { MAX_PDF_SIZE_BYTES } from '../../config/analysis.js'
import { assertAnalysis, assertDocument, assertResult, displayValue, formatExpiry, getAnalysisProgress, getUploadError, validatePdfSelection } from './uploadModel.js'
import { completedResult, readyDocument } from '../../test/analysisFixtures.js'

const file = (overrides = {}) => ({ name: 'test.pdf', type: 'application/pdf', size: 100, ...overrides })

describe('PDF selection and error instructions', () => {
  it('accepts one PDF up to the configured size boundary', () => {
    expect(validatePdfSelection([file()])).toBeNull()
    expect(validatePdfSelection([file({ size: MAX_PDF_SIZE_BYTES })])).toBeNull()
  })
  it.each([
    [[], 'FILE_COUNT'], [[file(), file()], 'FILE_COUNT'],
    [[file({ name: 'test.exe' })], 'NOT_PDF'], [[file({ type: 'image/png' })], 'NOT_PDF'],
    [[file({ size: MAX_PDF_SIZE_BYTES + 1 })], 'FILE_TOO_LARGE'], [[file({ size: 0 })], 'EMPTY_FILE'],
  ])('rejects invalid selection %#', (files, code) => {
    expect(validatePdfSelection(files).code).toBe(code)
  })
  it.each(['NOT_PDF', 'FILE_TOO_LARGE', 'NO_TEXT', 'NOT_TERMS', 'POLICY_DETECTED', 'STRUCTURE_FAILED', 'NOT_FOUND', 'NOT_READY', 'ANALYSIS_FAILED'])('provides a next action for %s', (code) => {
    expect(getUploadError({ code }).instruction.length).toBeGreaterThan(10)
  })
  it('does not claim policy deletion unless the server explicitly confirms it', () => {
    expect(getUploadError({ code: 'POLICY_DETECTED' }).deletionNotice).toContain('확인되지 않았습니다')
    expect(getUploadError({ reject: { code: 'POLICY_DETECTED' }, deleted: true }).deletionNotice).toContain('삭제했다고 응답')
    expect(getUploadError({ code: 'POLICY_DETECTED', fileDeleted: false }).deletionNotice).toContain('삭제되지 않았다고')
    expect(getUploadError({ code: 'POLICY_DETECTED', details: { error: { deleted: true } } }).deletionNotice).toContain('삭제했다고 응답')
  })
})

describe('server response and progress contracts', () => {
  it('requires matching IDs and actual terminal states', () => {
    expect(assertDocument(readyDocument, 'test-document')).toBe(readyDocument)
    expect(() => assertDocument(readyDocument, 'wrong')).toThrow()
    expect(() => assertDocument({ documentId: 'test', status: 'UNKNOWN' })).toThrow()
    expect(() => assertAnalysis({ analysisId: 'test', status: 'DONE' }, 'wrong')).toThrow()
    expect(() => assertResult({}, 'test-analysis')).toThrow()
    expect(() => assertResult({ ...completedResult, checkSummary: [] }, 'test-analysis')).toThrow()
    expect(() => assertResult({ ...completedResult, groups: [{ cards: [{ ...completedResult.groups[0].cards[0], title: { invalid: true } }] }] }, 'test-analysis')).toThrow()
    expect(() => assertResult({ ...completedResult, checkSummary: [{ checkItem: 1, status: 'FOUND', name: {} }] }, 'test-analysis')).toThrow()
    expect(assertResult(completedResult, 'test-analysis')).toBe(completedResult)
  })
  it('derives progress only from server steps and does not reach 100 before DONE', () => {
    const progress = getAnalysisProgress({ status: 'RUNNING', step: 'EXPLAINING', stepIndex: 3, stepCount: 4 })
    expect(progress.percent).toBe(75)
    expect(progress.stages.map((stage) => stage.state)).toEqual(['done', 'done', 'done', 'running', 'waiting'])
    expect(getAnalysisProgress({ status: 'QUEUED', stepIndex: 3, stepCount: 4 }).percent).toBe(0)
    expect(getAnalysisProgress({ status: 'RUNNING', step: 'FINALIZING', stepIndex: 4, stepCount: 4 }).percent).toBe(99)
    expect(getAnalysisProgress({ status: 'DONE' }).stages.every((stage) => stage.state === 'done')).toBe(true)
    expect(getAnalysisProgress({ status: 'DONE' }).percent).toBe(100)
    expect(getAnalysisProgress({ status: 'RUNNING', step: 'EXTRACTING' }).percent).toBe(0)
  })
  it('keeps earlier stages complete when a server-reported stage fails', () => {
    const progress = getAnalysisProgress({ status: 'FAILED', step: 'CHECKING_QUOTES', stepIndex: 2, stepCount: 4 })
    expect(progress.stages.map((stage) => stage.state)).toEqual(['done', 'done', 'failed', 'waiting', 'waiting'])
  })
  it('does not replace null document values with example data', () => {
    expect(displayValue(null)).toBe('확인되지 않음')
    expect(formatExpiry(null)).toBe('확인되지 않음')
    expect(formatExpiry('not-a-date')).toBe('확인되지 않음')
  })
})
