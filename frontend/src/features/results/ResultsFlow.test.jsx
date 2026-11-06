import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from '../../App.jsx'
import { ApiError, createSpringApi } from '../../api/springApi.js'
import { readAnalysisReference, rememberAnalysisId } from '../../services/analysisService.js'
import { completedResult, readyDocument } from '../../test/analysisFixtures.js'

function deferred() {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function makeApi(overrides = {}) {
  return {
    getDocument: vi.fn().mockResolvedValue(readyDocument),
    getAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status: 'DONE' }),
    getResult: vi.fn().mockResolvedValue(completedResult),
    getClause: vi.fn(async (documentId, clauseId) => {
      const card = completedResult.groups.flatMap((group) => group.cards).find((item) => item.clauseId === clauseId)
      return { clauseId, ...card.source, articleTitle: `원문 제목 ${clauseId}`, text: `  앞부분\n${card.quote}\n\n뒷부분  ` }
    }),
    deleteDocument: vi.fn().mockResolvedValue(null),
    ...overrides,
  }
}

function openResults({ api = makeApi(), path = '/results', remember = true } = {}) {
  if (remember) rememberAnalysisId(completedResult.analysisId, readyDocument)
  window.history.replaceState(null, '', path)
  render(<BrowserRouter><App api={api} /></BrowserRouter>)
  return { api, user: userEvent.setup() }
}

async function ready() { await screen.findByRole('link', { name: '확인 완료' }) }
async function openDelete(user) {
  await user.click(screen.getByRole('button', { name: '분석결과 삭제하기' }))
  return screen.getByRole('dialog', { name: '분석 데이터를 삭제하시겠습니까?' })
}

describe('Frame 45 result data and filters', () => {
  it('renders actual metadata, calculated counts and all six check items without inventing QUOTE_ONLY explanations', async () => {
    const { user, api } = openResults()
    await ready()
    expect(screen.getByText(readyDocument.productName)).toBeTruthy()
    expect(screen.getByText(readyDocument.versionLabel)).toBeTruthy()
    expect(screen.getByText(`${readyDocument.pageCount}쪽`)).toBeTruthy()
    expect([...document.querySelectorAll('.result-counter strong')].map((node) => node.textContent)).toEqual(['2', '1', '1'])
    expect(api.getResult).toHaveBeenCalledWith(completedResult.analysisId)
    expect(screen.getAllByRole('button', { name: '원문 보기' })).toHaveLength(2)
    expect(screen.getByText('특약 테스트 인용입니다.')).toBeTruthy()
    expect(screen.queryByText('표시 금지 테스트 제목')).toBeNull()
    expect(screen.queryByText('표시 금지 테스트 해설')).toBeNull()
    await user.click(screen.getByRole('button', { name: '위험 조항' }))
    expect(screen.getAllByRole('button', { name: '원문 보기' })).toHaveLength(1)
    expect(screen.queryByText('특약 테스트 인용입니다.')).toBeNull()
    expect(screen.queryByText('찾지 못함')).toBeNull()
    await user.click(screen.getByRole('button', { name: '점검 항목' }))
    const checks = screen.getByRole('region', { name: '6개 점검 항목 요약' })
    expect(within(checks).getAllByRole('heading')).toHaveLength(6)
    expect(within(checks).getAllByText('찾지 못했습니다. 직접 확인하세요')).toHaveLength(4)
    expect(screen.queryByRole('button', { name: '원문 보기' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '전체 조항' }))
    await user.selectOptions(screen.getByLabelText('중요도'), 'NORMAL')
    expect(screen.getAllByRole('button', { name: '원문 보기' })).toHaveLength(1)
    expect(screen.getByText('특약 테스트 인용입니다.')).toBeTruthy()
    await user.selectOptions(screen.getByLabelText('중요도'), 'ALL')
    expect(screen.getAllByRole('button', { name: '원문 보기' })).toHaveLength(2)
    expect(api.getResult).toHaveBeenCalledTimes(1)
    expect(screen.getByText('약관 해설이며 보험금 지급을 보장하거나 법률 자문을 대신하지 않습니다.')).toBeTruthy()
  })

  it('opens each card’s own clause, retains original whitespace and restores the results/filter/scroll on close', async () => {
    const { user, api } = openResults()
    await ready()
    window.scrollY = 1700
    const originalPage = screen.getByRole('main')
    const originalText = '  앞부분\n테스트 원문   인용입니다.\n\n뒷부분  '
    api.getClause.mockResolvedValueOnce({ clauseId: 'test-clause-1', section: '주계약', article: '제12조', paragraph: '제2항', page: 14, articleTitle: '첫 번째 조항', text: originalText })
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[0])
    const modal = screen.getByRole('dialog', { name: '원문 보기' })
    await within(modal).findByText('첫 번째 조항')
    expect(within(modal).getByText('꼭 확인')).toBeTruthy()
    expect(modal.querySelector('mark').textContent).toBe('테스트 원문   인용입니다.')
    expect(modal.querySelector('.clause-modal__text > p').textContent).toBe(originalText)
    expect(api.getClause.mock.calls[0].slice(0, 2)).toEqual(['test-document', 'test-clause-1'])
    expect(document.body.style.overflow).toBe('hidden')
    await user.click(within(modal).getByRole('button', { name: '닫기' }))
    expect(screen.getByRole('main')).toBe(originalPage)
    expect(window.scrollY).toBe(1700)
    expect(document.body.style.overflow).toBe('')
    expect(screen.getByRole('button', { name: '전체 조항' }).getAttribute('aria-pressed')).toBe('true')
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[1])
    const second = screen.getByRole('dialog')
    await within(second).findByText('원문 제목 test-clause-2')
    expect(api.getClause.mock.calls[1].slice(0, 2)).toEqual(['test-document', 'test-clause-2'])
    expect(second.querySelector('mark').textContent).toBe('특약 테스트 인용입니다.')
    expect(within(second).queryByText('AI 쉬운 해설')).toBeNull()
    expect(within(second).queryByText('표시 금지 테스트 해설')).toBeNull()
    fireEvent(second, new Event('cancel', { cancelable: true }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(window.location.pathname).toBe('/results')
    expect(api.getResult).toHaveBeenCalledTimes(1)
  })

  it('shows clause lookup failures, supports retry and rejects the wrong clause', async () => {
    const api = makeApi({ getClause: vi.fn().mockResolvedValueOnce({ clauseId: 'wrong-clause', text: '다른 조항' }).mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '원문 연결 실패')).mockResolvedValue({ clauseId: 'test-clause-1', text: '인용 없음\n전체 원문 유지', articleTitle: '다시 조회한 원문' }) })
    const { user } = openResults({ api })
    await ready()
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[0])
    const modal = screen.getByRole('dialog')
    await within(modal).findByRole('alert')
    expect(within(modal).queryByText('다른 조항')).toBeNull()
    await user.click(within(modal).getByRole('button', { name: '다시 불러오기' }))
    await within(modal).findByText('원문 연결 실패')
    await user.click(within(modal).getByRole('button', { name: '다시 불러오기' }))
    await within(modal).findByText('다시 조회한 원문')
    expect(modal.querySelector('mark')).toBeNull()
    expect(modal.querySelector('.clause-modal__text > p').textContent).toBe('인용 없음\n전체 원문 유지')
    expect(within(modal).getByText(/인용문의 위치를 확인하지 못했습니다/)).toBeTruthy()
  })
})

describe('Frame 53 deletion and confirmation navigation', () => {
  it('only opens the confirmation, and cancel leaves the results, reference and scroll intact', async () => {
    const { user, api } = openResults()
    await ready()
    await user.click(screen.getByRole('button', { name: '위험 조항' }))
    const stored = sessionStorage.getItem('deulda.analysisReference')
    window.scrollY = 2400
    const modal = await openDelete(user)
    expect(api.deleteDocument).not.toHaveBeenCalled()
    expect(within(modal).getByText(/삭제한 데이터는 복구할 수 없습니다/)).toBeTruthy()
    await user.click(within(modal).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText('검증된 테스트 해설입니다.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '위험 조항' }).getAttribute('aria-pressed')).toBe('true')
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(stored)
    expect(window.scrollY).toBe(2400)
    expect(api.deleteDocument).not.toHaveBeenCalled()
  })

  it('waits for the 204 HTTP response, prevents duplicate deletion and clears memory/storage before returning to upload', async () => {
    const deletion = deferred()
    const fetchImpl = vi.fn(async (url, options) => {
      if (options.method === 'DELETE') return deletion.promise
      const data = url.endsWith('/result') ? completedResult : url.startsWith('/api/analyses/') ? { analysisId: completedResult.analysisId, status: 'DONE' } : readyDocument
      return { ok: true, status: 200, text: async () => JSON.stringify(data) }
    })
    const { user } = openResults({ api: createSpringApi({ baseUrl: '', fetchImpl }) })
    await ready()
    const modal = await openDelete(user)
    const confirm = within(modal).getByRole('button', { name: '삭제하기' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(within(modal).getByRole('button', { name: '삭제 요청 중…' }).disabled).toBe(true)
    expect(within(modal).getByRole('button', { name: '닫기' }).disabled).toBe(true)
    fireEvent(modal, new Event('cancel', { cancelable: true }))
    expect(screen.getByRole('dialog')).toBe(modal)
    expect(fetchImpl.mock.calls.filter(([, options]) => options.method === 'DELETE')).toHaveLength(1)
    expect(fetchImpl.mock.calls.find(([, options]) => options.method === 'DELETE')[0]).toBe('/api/documents/test-document')
    expect(window.location.pathname).toBe('/results')
    expect(readAnalysisReference()).not.toBeNull()
    await act(async () => deletion.resolve({ ok: true, status: 204, text: async () => '' }))
    await waitFor(() => expect(window.location.pathname).toBe('/upload'))
    expect(readAnalysisReference()).toBeNull()
    expect(sessionStorage.getItem('deulda.analysisReference')).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    expect(await screen.findByRole('dialog', { name: '결과를 확인할 수 없습니다' })).toBeTruthy()
    expect(window.location.pathname).toBe('/upload')
  })

  it('retains the results after a failed deletion and allows cancellation or a retry', async () => {
    const api = makeApi({ deleteDocument: vi.fn().mockRejectedValueOnce(new ApiError('DELETE_FAILED', '서버에서 삭제를 완료하지 못했습니다.')).mockResolvedValue(null) })
    const { user } = openResults({ api })
    await ready()
    const stored = sessionStorage.getItem('deulda.analysisReference')
    const modal = await openDelete(user)
    await user.click(within(modal).getByRole('button', { name: '삭제하기' }))
    expect((await within(modal).findByRole('alert')).textContent).toBe('서버에서 삭제를 완료하지 못했습니다.')
    expect(window.location.pathname).toBe('/results')
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(stored)
    expect(screen.getByText('검증된 테스트 해설입니다.')).toBeTruthy()
    expect(within(modal).getByRole('button', { name: '닫기' }).disabled).toBe(false)
    await user.click(within(modal).getByRole('button', { name: '삭제하기' }))
    await waitFor(() => expect(window.location.pathname).toBe('/upload'))
    expect(api.deleteDocument).toHaveBeenCalledTimes(2)
  })

  it('returns to upload immediately on 확인 완료 without deletion and reopens a still-available result from the header', async () => {
    const { user, api } = openResults()
    await ready()
    const reference = sessionStorage.getItem('deulda.analysisReference')
    await user.click(screen.getByRole('link', { name: '확인 완료' }))
    expect(window.location.pathname).toBe('/upload')
    expect(api.deleteDocument).not.toHaveBeenCalled()
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(reference)
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    await ready()
    expect(window.location.pathname).toBe('/results')
    expect(screen.getByText(readyDocument.productName)).toBeTruthy()
    expect(api.getResult.mock.calls.length).toBeGreaterThan(1)
  })
})

describe('explicit development preview', () => {
  it('does not silently insert examples when no result exists', async () => {
    openResults({ remember: false })
    expect(await screen.findByRole('link', { name: '개발용 결과 미리보기' })).toBeTruthy()
    expect(screen.queryByRole('complementary', { name: '미리보기 데이터' })).toBeNull()
    expect(screen.queryByRole('link', { name: '확인 완료' })).toBeNull()
  })

  it('identifies preview results, filters them, opens local long clauses and only clears example data on deletion', async () => {
    const { user, api } = openResults({ remember: false, path: '/results?preview=1' })
    await ready()
    expect(screen.getByRole('complementary', { name: '미리보기 데이터' })).toBeTruthy()
    expect([...document.querySelectorAll('.result-counter strong')].map((node) => node.textContent)).toEqual(['3', '2', '1'])
    expect(readAnalysisReference()).toBeNull()
    await user.click(screen.getByRole('button', { name: '위험 조항' }))
    expect(screen.getAllByRole('button', { name: '원문 보기' })).toHaveLength(2)
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[1])
    const modal = screen.getByRole('dialog')
    await within(modal).findByRole('heading', { name: '갱신 시 보험료 변경' })
    expect(modal.querySelector('mark').textContent).toBe('갱신계약의 보험료는 갱신일 현재의 보험료율을 적용합니다.')
    expect(modal.querySelector('.clause-modal__text > p').textContent.length).toBeGreaterThan(1000)
    await user.click(within(modal).getByRole('button', { name: '닫기' }))
    const deletion = await openDelete(user)
    expect(within(deletion).getByText(/서버 삭제 요청은 전송하지 않습니다/)).toBeTruthy()
    await user.click(within(deletion).getByRole('button', { name: '삭제하기' }))
    await waitFor(() => expect(window.location.pathname).toBe('/upload'))
    expect(api.getResult).not.toHaveBeenCalled()
    expect(api.getClause).not.toHaveBeenCalled()
    expect(api.deleteDocument).not.toHaveBeenCalled()
    expect(readAnalysisReference()).toBeNull()
  })

  it('isolates preview from available server data without calling the live API', async () => {
    const { api } = openResults({ path: '/results?preview=1' })
    await ready()
    expect(screen.queryByText(readyDocument.productName)).toBeNull()
    expect(screen.getByText('(예시) 종합건강보험')).toBeTruthy()
    expect(screen.getByRole('complementary', { name: '미리보기 데이터' })).toBeTruthy()
    expect(api.getAnalysis).not.toHaveBeenCalled()
    expect(api.getResult).not.toHaveBeenCalled()
  })

  it('does not call an unavailable server or clear its stored reference when discarding preview data', async () => {
    const { user, api } = openResults({ path: '/results?preview=1', api: makeApi({ getAnalysis: vi.fn().mockRejectedValue(new ApiError('NETWORK_ERROR', '백엔드 미연결')) }) })
    await ready()
    expect(screen.getByRole('complementary', { name: '미리보기 데이터' })).toBeTruthy()
    const stored = sessionStorage.getItem('deulda.analysisReference')
    const modal = await openDelete(user)
    await user.click(within(modal).getByRole('button', { name: '삭제하기' }))
    await waitFor(() => expect(window.location.pathname).toBe('/upload'))
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(stored)
    expect(api.deleteDocument).not.toHaveBeenCalled()
  })
})
