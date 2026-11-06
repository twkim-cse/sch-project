import { describe, expect, it } from 'vitest'
import { splitQuote } from './resultModel.js'
import { forgetAnalysisReference, readAnalysisReference, rememberAnalysisId } from '../../services/analysisService.js'

describe('original clause highlighting', () => {
  it.each([
    ['  앞부분\n원문   인용\r\n내용\t입니다.\n\n뒷부분  ', '원문 인용 내용 입니다.', '원문   인용\r\n내용\t입니다.'],
    ['\n인용 없이  전체 원문을 보존\n', '없는 문구', ''],
    ['  앞 😀 원문\n😀 끝  ', '😀 원문 😀', '😀 원문\n😀'],
    [' \n 원문  ', '', ''],
  ])('keeps the exact response text while matching PDF whitespace %#', (original, quote, expected) => {
    const parts = splitQuote(original, quote)
    expect(parts.quote).toBe(expected)
    expect(parts.before + parts.quote + parts.after).toBe(original)
  })
})

describe('deleted analysis reference', () => {
  it('forgets the deleted document but preserves unrelated storage', () => {
    rememberAnalysisId('old-analysis', { documentId: 'old-doc' })
    sessionStorage.setItem('other-app-key', 'keep')
    expect(forgetAnalysisReference('old-analysis', 'old-doc')).toBe(true)
    expect(readAnalysisReference()).toBeNull()
    expect(sessionStorage.getItem('deulda.analysisId')).toBeNull()
    expect(sessionStorage.getItem('deulda.analysisReference')).toBeNull()
    expect(sessionStorage.getItem('other-app-key')).toBe('keep')
  })
  it('does not discard a newer document when an earlier deletion completes', () => {
    rememberAnalysisId('new-analysis', { documentId: 'new-doc' })
    expect(forgetAnalysisReference('old-analysis', 'old-doc')).toBe(false)
    expect(readAnalysisReference()).toMatchObject({ analysisId: 'new-analysis', documentId: 'new-doc' })
  })
})
