import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from '../App.jsx'
import { createSpringApi } from '../api/springApi.js'
import { rememberAnalysisId } from '../services/analysisService.js'
import { completedResult, readyDocument } from './analysisFixtures.js'

function setup({ path = '/upload', document = readyDocument, analysis, result = completedResult, clause } = {}) {
  const replies = {
    document, result,
    analysis: analysis || { analysisId: completedResult.analysisId, status: 'DONE' },
    clause: clause || { clauseId: 'test-clause-1', text: '테스트 원문 인용입니다.', articleTitle: '정상 원문 제목' },
  }
  const fetchImpl = vi.fn(async (url, options) => {
    let body, status = 200
    if (url === '/api/documents' && options.method === 'POST') { body = { documentId: readyDocument.documentId, status: 'CHECKING' }; status = 202 }
    else if (url === '/api/analyses' && options.method === 'POST') { body = { analysisId: completedResult.analysisId, status: 'QUEUED' }; status = 202 }
    else if (url.includes('/clauses/')) body = replies.clause
    else if (url.endsWith('/result')) body = replies.result
    else if (url.startsWith('/api/analyses/')) body = replies.analysis
    else body = replies.document
    return { ok: true, status, text: async () => JSON.stringify(body) }
  })
  if (path === '/results') rememberAnalysisId(completedResult.analysisId, readyDocument)
  window.history.replaceState(null, '', path)
  render(<BrowserRouter><App api={createSpringApi({ baseUrl: '', fetchImpl })} /></BrowserRouter>)
  return { user: userEvent.setup(), replies, fetchImpl }
}
async function upload(user) {
  await user.upload(screen.getByLabelText('보험약관 PDF 파일 선택'), new File(['%PDF test'], 'terms.pdf', { type: 'application/pdf' }))
  await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
}
async function startAnalysis(user) {
  await upload(user)
  await user.click(await screen.findByRole('button', { name: '확인하고 다음으로' }))
  await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
}

describe('invalid HTTP payloads follow existing UI error and retry flows', () => {
  it.each(['object pageStart', 'missing document metadata', 'wrong riders container'])('rejects %s before rendering and supports a fresh upload', async (kind) => {
    const invalid = structuredClone(readyDocument)
    if (kind === 'object pageStart') invalid.riders[0].pageStart = { value: 85 }
    if (kind === 'missing document metadata') delete invalid.pageCount
    if (kind === 'wrong riders container') invalid.riders = {}
    const { user, replies, fetchImpl } = setup({ document: invalid })
    await upload(user)
    expect(await screen.findByText('오류 코드: INVALID_RESPONSE')).toBeTruthy()
    expect(screen.getByRole('dialog', { name: '파일을 확인할 수 없습니다' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '확인하고 다음으로' })).toBeNull()
    expect(fetchImpl.mock.calls.some(([url]) => url === '/api/analyses')).toBe(false)
    replies.document = readyDocument
    await user.click(screen.getByRole('button', { name: '다시 시도하기' }))
    await upload(user)
    await user.click(await screen.findByRole('button', { name: '확인하고 다음으로' }))
    expect(screen.getByRole('checkbox', { name: /테스트 진단 특별약관/ })).toBeTruthy()
    expect(screen.getByText('85쪽')).toBeTruthy()
  })

  it('rejects string stepIndex and resumes through the existing status retry', async () => {
    const { user, replies, fetchImpl } = setup({ analysis: { analysisId: completedResult.analysisId, status: 'RUNNING', step: 'EXPLAINING', stepIndex: '3', stepCount: 4 } })
    await startAnalysis(user)
    expect((await screen.findByRole('alert')).textContent).toContain('분석 진행 단계 응답 형식')
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    expect(fetchImpl.mock.calls.some(([url]) => url.endsWith('/result'))).toBe(false)
    replies.analysis = { analysisId: completedResult.analysisId, status: 'DONE' }
    await user.click(screen.getByRole('button', { name: '상태 다시 확인' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false))
    await user.click(screen.getByRole('button', { name: '결과 확인하기' }))
    expect(await screen.findByText('검증된 테스트 해설입니다.')).toBeTruthy()
  })

  it('rejects string fromTable in completed upload results, then retries without starting another analysis', async () => {
    const invalid = structuredClone(completedResult)
    invalid.groups[0].cards[0].fromTable = 'false'
    const { user, replies, fetchImpl } = setup({ result: invalid })
    await startAnalysis(user)
    expect((await screen.findByRole('alert')).textContent).toContain('분석 결과 카드 형식')
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    replies.result = completedResult
    await user.click(screen.getByRole('button', { name: '결과 다시 불러오기' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false))
    expect(fetchImpl.mock.calls.filter(([url, options]) => url === '/api/analyses' && options.method === 'POST')).toHaveLength(1)
  })

  it.each(['string fromTable', 'invalid source'])('rejects %s on result re-entry without rendering cards', async (kind) => {
    const invalid = structuredClone(completedResult)
    if (kind === 'string fromTable') invalid.groups[0].cards[0].fromTable = 'false'
    else invalid.groups[0].cards[0].source = []
    const { user, replies } = setup({ path: '/results', result: invalid })
    expect((await screen.findByRole('alert')).textContent).toContain('분석 결과 카드 형식')
    expect(screen.queryByRole('button', { name: '원문 보기' })).toBeNull()
    replies.result = completedResult
    await user.click(screen.getByRole('button', { name: '결과 다시 불러오기' }))
    expect(await screen.findByText('검증된 테스트 해설입니다.')).toBeTruthy()
    expect(screen.getAllByText('표 내용이므로 원문 확인 권장')).toHaveLength(1)
  })

  it('rejects an object articleTitle inside the original-text overlay and recovers on retry', async () => {
    const { user, replies } = setup({ path: '/results', clause: { clauseId: 'test-clause-1', text: '원문', articleTitle: { value: 'bad' } } })
    await screen.findByRole('link', { name: '확인 완료' })
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[0])
    const modal = screen.getByRole('dialog', { name: '원문 보기' })
    expect((await within(modal).findByRole('alert')).textContent).toContain('원문 응답 형식')
    expect(screen.getByText('검증된 테스트 해설입니다.')).toBeTruthy()
    replies.clause = { clauseId: 'test-clause-1', text: '테스트 원문 인용입니다.', articleTitle: null }
    await user.click(within(modal).getByRole('button', { name: '다시 불러오기' }))
    await within(modal).findByRole('heading', { name: '약관 원문', level: 3 })
    expect(modal.querySelector('mark').textContent).toBe('테스트 원문 인용입니다.')
    await user.click(within(modal).getByRole('button', { name: '닫기' }))
    expect(screen.getByRole('link', { name: '확인 완료' })).toBeTruthy()
  })
})