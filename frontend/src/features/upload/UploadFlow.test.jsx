import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.jsx'
import { ApiError, createSpringApi } from '../../api/springApi.js'
import { DOCUMENT_POLL_INTERVAL_MS, ANALYSIS_POLL_INTERVAL_MS, MAX_PDF_SIZE_BYTES } from '../../config/analysis.js'
import { completedResult, readyDocument } from '../../test/analysisFixtures.js'

function deferred() {
  let resolve
  let reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function makeApi(overrides = {}) {
  return {
    uploadDocument: vi.fn().mockResolvedValue({ documentId: readyDocument.documentId, status: 'CHECKING' }),
    getDocument: vi.fn().mockResolvedValue(readyDocument),
    startAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status: 'QUEUED' }),
    getAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status: 'DONE' }),
    getResult: vi.fn().mockResolvedValue(completedResult),
    getClause: vi.fn().mockResolvedValue({ clauseId: 'test-clause-1', section: '주계약', article: '제12조', page: 14, text: '앞부분\n테스트 원문   인용입니다. 뒷부분' }),
    ...overrides,
  }
}

function renderUpload(api = makeApi(), { strict = false, path = '/upload' } = {}) {
  window.history.replaceState(null, '', path)
  // RTL's async user-event wrapper drains a real timer. Polling tests use
  // direct DOM events plus act so their fake clock controls only API polling.
  const user = vi.isFakeTimers() ? {
    async upload(input, file) { fireEvent.change(input, { target: { files: [file] } }); await flush() },
    async click(element) { fireEvent.click(element); await flush() },
  } : userEvent.setup({ applyAccept: false })
  const app = <BrowserRouter><App api={api} /></BrowserRouter>
  const view = render(strict ? <StrictMode>{app}</StrictMode> : app)
  return { user, api, ...view }
}

const pdf = () => new File(['%PDF-1.7 test-only file'], '테스트약관.pdf', { type: 'application/pdf' })
const choose = async (user, file = pdf()) => user.upload(screen.getByLabelText('보험약관 PDF 파일 선택'), file)
const flush = async () => act(async () => { await Promise.resolve(); await Promise.resolve() })

async function goToRiders(user) {
  await choose(user)
  await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
  await flush()
  await user.click(screen.getByRole('button', { name: '확인하고 다음으로' }))
}

afterEach(() => vi.useRealTimers())

describe('Frame 17 file controls and Frame 50', () => {
  it('wires the file picker, renders metadata, and supports clearing/reselecting one PDF', async () => {
    const { user, api } = renderUpload()
    const input = screen.getByLabelText('보험약관 PDF 파일 선택')
    const click = vi.spyOn(input, 'click').mockImplementation(() => {})
    await user.click(screen.getByRole('button', { name: '파일 업로드 하기' }))
    expect(click).toHaveBeenCalledTimes(1)
    expect(input.multiple).toBe(false)
    expect(screen.getByRole('button', { name: '업로드 시작하기' }).disabled).toBe(true)
    expect(screen.queryByRole('button', { name: '취소' })).toBeNull()
    click.mockRestore()
    await choose(user)
    const info = screen.getByLabelText('선택한 파일 정보')
    expect(within(info).getByText('테스트약관.pdf')).toBeTruthy()
    expect(within(info).getByText(/application\/pdf/)).toBeTruthy()
    expect(within(info).getByText(/bytes/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '업로드 시작하기' }).disabled).toBe(false)
    await user.click(screen.getByRole('button', { name: '선택 파일 삭제' }))
    expect(screen.queryByLabelText('선택한 파일 정보')).toBeNull()
    expect(screen.getByRole('button', { name: '업로드 시작하기' }).disabled).toBe(true)
    await choose(user)
    expect(screen.getByText('테스트약관.pdf')).toBeTruthy()
    expect(api.uploadDocument).not.toHaveBeenCalled()
    expect(window.localStorage.length).toBe(0)
  })

  it('accepts a drag-and-drop PDF and rejects multiple dropped files', async () => {
    const { user } = renderUpload()
    const dropzone = screen.getByLabelText('PDF 드래그 앤 드롭 영역')
    fireEvent.drop(dropzone, { dataTransfer: { files: [pdf()] } })
    expect(screen.getByText('테스트약관.pdf')).toBeTruthy()
    fireEvent.drop(dropzone, { dataTransfer: { files: [pdf(), pdf()] } })
    expect(screen.getByRole('dialog', { name: '파일을 확인할 수 없습니다' })).toBeTruthy()
    expect(screen.getByRole('alert').textContent).toContain('한 개만')
    await user.click(screen.getByRole('button', { name: '다시 시도하기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(window.location.pathname).toBe('/upload')
    expect(screen.getByRole('button', { name: '업로드 시작하기' }).disabled).toBe(true)
  })

  it.each([
    ['NOT_PDF', () => new File(['text'], 'test.txt', { type: 'text/plain' })],
    ['FILE_TOO_LARGE', () => { const file = pdf(); Object.defineProperty(file, 'size', { value: MAX_PDF_SIZE_BYTES + 1 }); return file }],
  ])('uses Frame 50 for local %s and closes back to file selection', async (code, createFile) => {
    const { user, api } = renderUpload()
    await choose(user, createFile())
    expect(screen.getByRole('dialog', { name: '파일을 확인할 수 없습니다' })).toBeTruthy()
    expect(screen.getByText(`오류 코드: ${code}`)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.uploadDocument).not.toHaveBeenCalled()
  })

  it.each(['NO_TEXT', 'NOT_TERMS', 'POLICY_DETECTED', 'STRUCTURE_FAILED'])('renders server document rejection %s without advancing', async (code) => {
    const api = makeApi({ getDocument: vi.fn().mockResolvedValue({ documentId: readyDocument.documentId, status: 'REJECTED', reject: { code, message: '테스트 거절 이유' } }) })
    const { user } = renderUpload(api)
    await choose(user)
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    expect(await screen.findByRole('dialog', { name: '파일을 확인할 수 없습니다' })).toBeTruthy()
    expect(screen.getByText(`오류 코드: ${code}`)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '확인하고 다음으로' })).toBeNull()
    expect(api.startAnalysis).not.toHaveBeenCalled()
    if (code === 'POLICY_DETECTED') expect(screen.getByText('서버의 파일 삭제 여부는 확인되지 않았습니다.')).toBeTruthy()
  })

  it('shows confirmed deletion only when the rejection response supplies it', async () => {
    const api = makeApi({ getDocument: vi.fn().mockResolvedValue({ documentId: readyDocument.documentId, status: 'REJECTED', reject: { code: 'POLICY_DETECTED' }, deleted: true }) })
    const { user } = renderUpload(api)
    await choose(user)
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    expect(await screen.findByText('서버에서 해당 파일을 삭제했다고 응답했습니다.')).toBeTruthy()
  })

  it('reports a backend connection failure instead of pretending the document is ready', async () => {
    const api = makeApi({ uploadDocument: vi.fn().mockRejectedValue(new ApiError('NETWORK_ERROR', '백엔드 연결 대기: 테스트 서버 없음')) })
    const { user } = renderUpload(api)
    await choose(user)
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    expect(await screen.findByRole('dialog', { name: '파일을 확인할 수 없습니다' })).toBeTruthy()
    expect(screen.getAllByText(/백엔드 연결 대기/).length).toBeGreaterThan(0)
    expect(api.getDocument).not.toHaveBeenCalled()
  })
})

describe('server-driven document and analysis flow', () => {
  it('keeps the same modal and upload backdrop through 24 → 25 → 26 → 27 → actual results', async () => {
    const upload = deferred()
    const result = deferred()
    const api = makeApi({
      uploadDocument: vi.fn().mockReturnValue(upload.promise),
      getAnalysis: vi.fn().mockResolvedValueOnce({ analysisId: completedResult.analysisId, status: 'RUNNING', step: 'EXPLAINING', stepIndex: 3, stepCount: 4 }).mockResolvedValue({ analysisId: completedResult.analysisId, status: 'DONE' }),
      getResult: vi.fn().mockReturnValueOnce(result.promise).mockResolvedValue(completedResult),
    })
    const { user } = renderUpload(api, { strict: true })
    await choose(user)
    const backdropPage = screen.getByRole('heading', { level: 1, name: '보험약관 분석하기' })
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    const modal = screen.getByRole('dialog', { name: '문서 확인중 ...' })
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    expect(api.uploadDocument).toHaveBeenCalledTimes(1)
    await act(async () => upload.resolve({ documentId: readyDocument.documentId, status: 'CHECKING' }))
    expect(await screen.findByRole('dialog', { name: '보험 약관 확인 완료' })).toBe(modal)
    expect(screen.getByText(readyDocument.productName)).toBeTruthy()
    expect(screen.getByText(readyDocument.versionLabel)).toBeTruthy()
    expect(screen.getByText('212쪽')).toBeTruthy()
    expect(screen.getByText(/2099/)).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '확인하고 다음으로' }))
    expect(screen.getByRole('dialog', { name: '가입한 특약 선택' })).toBe(modal)
    expect(screen.getByLabelText('주계약 항상 포함')).toBeTruthy()
    expect(screen.getAllByRole('checkbox')).toHaveLength(3)
    await user.click(screen.getByText(readyDocument.riders[0].name))
    expect(screen.getByRole('checkbox', { name: /테스트 진단 특별약관/ }).checked).toBe(true)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    expect(await screen.findByRole('dialog', { name: 'AI가 보험약관을 분석하고 있습니다' })).toBe(modal)
    expect(api.startAnalysis.mock.calls[0][0]).toEqual({ documentId: readyDocument.documentId, riderIds: ['test-rider-1'], viewAll: false })
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('75')
    expect(screen.getAllByText('완료')).toHaveLength(3)
    expect(screen.getByText('진행 중')).toBeTruthy()
    expect(screen.getByText('대기 중')).toBeTruthy()
    expect(document.contains(backdropPage)).toBe(true)
    expect(window.location.pathname).toBe('/upload')
    await waitFor(() => expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('100'), { timeout: 5000 })
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    await act(async () => result.resolve(completedResult))
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false)
    await user.click(screen.getByRole('button', { name: '결과 확인하기' }))
    await waitFor(() => expect(window.location.pathname).toBe('/results'))
    expect(await screen.findByText(readyDocument.productName)).toBeTruthy()
    expect(screen.getByText('검증된 테스트 해설입니다.')).toBeTruthy()
    expect(screen.queryByText('표시 금지 테스트 제목')).toBeNull()
    expect(screen.queryByText('표시 금지 테스트 해설')).toBeNull()
    expect(screen.getAllByText(/원문 확인 필요/).length).toBeGreaterThan(0)
    expect(screen.getAllByText('찾지 못함').length).toBeGreaterThan(0)
    expect(screen.getByText('약관 해설이며 보험금 지급을 보장하거나 법률 자문을 대신하지 않습니다.')).toBeTruthy()
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[0])
    expect(await screen.findByRole('dialog', { name: '원문 보기' })).toBeTruthy()
    await waitFor(() => expect(document.querySelector('mark')?.textContent).toBe('테스트 원문   인용입니다.'))
    expect(api.getClause.mock.calls[0].slice(0, 2)).toEqual(['test-document', 'test-clause-1'])
    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(window.location.pathname).toBe('/results')
    expect(window.sessionStorage.getItem('deulda.analysisReference')).not.toContain('테스트 전용 보험상품')
  }, 15000)

  it('keeps CHECKING until READY and never advances just because time passed', async () => {
    vi.useFakeTimers()
    const api = makeApi({ getDocument: vi.fn().mockResolvedValue({ documentId: readyDocument.documentId, status: 'CHECKING' }) })
    const { user } = renderUpload(api)
    await choose(user)
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    await flush()
    await act(async () => vi.advanceTimersByTimeAsync(DOCUMENT_POLL_INTERVAL_MS * 3))
    expect(screen.getByRole('dialog', { name: '문서 확인중 ...' })).toBeTruthy()
    expect(api.getDocument).toHaveBeenCalledTimes(4)
    api.getDocument.mockResolvedValue(readyDocument)
    await act(async () => vi.advanceTimersByTimeAsync(DOCUMENT_POLL_INTERVAL_MS))
    expect(screen.getByRole('dialog', { name: '보험 약관 확인 완료' })).toBeTruthy()
  })

  it('shows null metadata as unconfirmed and supports viewAll without losing individual selections', async () => {
    const api = makeApi({ getDocument: vi.fn().mockResolvedValueOnce({ ...readyDocument, productName: null, versionLabel: null, pageCount: null, expiresAt: null }).mockResolvedValue(readyDocument) })
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByRole('checkbox', { name: /테스트 진단 특별약관/ }))
    const all = screen.getByRole('checkbox', { name: '전체 보기' })
    await user.click(all)
    expect(screen.getByRole('checkbox', { name: /테스트 진단 특별약관/ }).disabled).toBe(true)
    await user.click(all)
    expect(screen.getByRole('checkbox', { name: /테스트 진단 특별약관/ }).checked).toBe(true)
    await user.click(all)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    expect(api.startAnalysis.mock.calls[0][0]).toEqual({ documentId: readyDocument.documentId, riderIds: [], viewAll: true })
  })

  it('renders null fields in the confirmation panel without example values', async () => {
    const api = makeApi({ getDocument: vi.fn().mockResolvedValue({ ...readyDocument, productName: null, versionLabel: null, pageCount: null, riders: null, expiresAt: null }) })
    const { user } = renderUpload(api)
    await choose(user)
    await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    expect((await screen.findAllByText('확인되지 않음')).length).toBe(5)
    expect(screen.queryByText(readyDocument.productName)).toBeNull()
  })

  it('stops on FAILED, displays the server error and never requests a result', async () => {
    const api = makeApi({ getAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status: 'FAILED', error: { code: 'ANALYSIS_FAILED', message: '테스트 서버 분석 실패' } }) })
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    expect(await screen.findByText(/테스트 서버 분석 실패/)).toBeTruthy()
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    expect(api.getResult).not.toHaveBeenCalled()
  })

  it('supports selecting several rider rows and sends their real IDs once', async () => {
    const api = makeApi()
    const pending = deferred()
    api.startAnalysis.mockReturnValue(pending.promise)
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByText(readyDocument.riders[0].name))
    await user.click(screen.getByText(readyDocument.riders[1].name))
    expect(screen.getByText('선택한 특약 2개')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    await user.click(screen.getByRole('button', { name: '분석 요청 중 ...' }))
    expect(api.startAnalysis).toHaveBeenCalledTimes(1)
    expect(api.startAnalysis.mock.calls[0][0].riderIds).toEqual(['test-rider-1', 'test-rider-2'])
    await user.click(screen.getByRole('button', { name: '닫기' }))
    await act(async () => pending.resolve({ analysisId: 'late-analysis', status: 'QUEUED' }))
    expect(api.getAnalysis).not.toHaveBeenCalled()
  })

  it('can resume a failed status lookup without posting another analysis', async () => {
    const api = makeApi({ getAnalysis: vi.fn().mockRejectedValueOnce(new ApiError('NETWORK_ERROR', 'temporary test failure')).mockResolvedValue({ analysisId: completedResult.analysisId, status: 'DONE' }) })
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    await user.click(await screen.findByRole('button', { name: '상태 다시 확인' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false))
    expect(api.startAnalysis).toHaveBeenCalledTimes(1)
  })

  it('never enables result navigation for a successful HTTP response with an invalid result body', async () => {
    const api = makeApi({ getResult: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, summary: 'not the result schema' }) })
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    expect(await screen.findByRole('button', { name: '결과 다시 불러오기' })).toBeTruthy()
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    expect(window.location.pathname).toBe('/upload')
  })

  it('keeps DONE results disabled after a result error and allows a real retry', async () => {
    const api = makeApi({ getResult: vi.fn().mockRejectedValueOnce(new ApiError('NOT_READY', '테스트 결과 준비 중')).mockResolvedValue(completedResult) })
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    const retry = await screen.findByRole('button', { name: '결과 다시 불러오기' })
    expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    await user.click(retry)
    await waitFor(() => expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false))
    expect(api.startAnalysis).toHaveBeenCalledTimes(1)
  })

  it('keeps the bar unchanged when the server reports the same step repeatedly', async () => {
    vi.useFakeTimers()
    const api = makeApi({ getAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status: 'RUNNING', step: 'EXTRACTING', stepIndex: 1, stepCount: 4 }) })
    const { user } = renderUpload(api)
    await goToRiders(user)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    await flush()
    await act(async () => vi.advanceTimersByTimeAsync(ANALYSIS_POLL_INTERVAL_MS * 3))
    expect(screen.getByRole('progressbar').getAttribute('aria-valuenow')).toBe('25')
    expect(api.getResult).not.toHaveBeenCalled()
  })

  it.each(['checking', 'confirmation', 'riders', 'progress', 'error'])('cleans up the %s overlay and stays on Frame 17', async (phase) => {
    vi.useFakeTimers()
    const pending = deferred()
    const api = makeApi({
      ...(phase === 'checking' ? { uploadDocument: vi.fn().mockReturnValue(pending.promise) } : {}),
      getAnalysis: vi.fn().mockResolvedValue({ analysisId: completedResult.analysisId, status: 'QUEUED' }),
    })
    const { user } = renderUpload(api)
    if (phase === 'error') await choose(user, new File(['text'], 'bad.txt', { type: 'text/plain' }))
    else {
      await choose(user)
      await user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
      await flush()
      if (['riders', 'progress'].includes(phase)) await user.click(screen.getByRole('button', { name: '확인하고 다음으로' }))
      if (phase === 'progress') { await user.click(screen.getByRole('button', { name: '분석 시작하기' })); await flush() }
    }
    expect(screen.getByRole('dialog')).toBeTruthy()
    const calls = api.getDocument.mock.calls.length + api.getAnalysis.mock.calls.length
    await user.click(screen.getByRole('button', { name: '닫기' }))
    await act(async () => vi.advanceTimersByTimeAsync(10000))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(window.location.pathname).toBe('/upload')
    expect(screen.getByRole('button', { name: '업로드 시작하기' }).disabled).toBe(true)
    expect(api.getDocument.mock.calls.length + api.getAnalysis.mock.calls.length).toBe(calls)
    if (phase === 'checking') {
      expect(api.uploadDocument.mock.calls[0][1].signal.aborted).toBe(true)
      await act(async () => pending.resolve({ documentId: 'late-doc', status: 'CHECKING' }))
      expect(api.getDocument).not.toHaveBeenCalled()
    }
    if (phase === 'progress') expect(screen.getByText(/서버에서 이미 시작된 분석은 계속될 수 있습니다/)).toBeTruthy()
  })

  it('can run through the real HTTP adapter with test-only fetch responses', async () => {
    const fetchImpl = vi.fn(async (url, options) => {
      let data
      if (url === '/api/documents' && options.method === 'POST') data = { documentId: readyDocument.documentId, status: 'CHECKING' }
      else if (url === '/api/analyses' && options.method === 'POST') data = { analysisId: completedResult.analysisId, status: 'QUEUED' }
      else if (url.endsWith('/result')) data = completedResult
      else if (url.startsWith('/api/analyses/')) data = { analysisId: completedResult.analysisId, status: 'DONE' }
      else data = readyDocument
      return { ok: true, status: 200, text: async () => JSON.stringify(data) }
    })
    const { user } = renderUpload(createSpringApi({ fetchImpl, baseUrl: '' }))
    await goToRiders(user)
    await user.click(screen.getByRole('button', { name: '분석 시작하기' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false))
    await user.click(screen.getByRole('button', { name: '결과 확인하기' }))
    expect(await screen.findByText(readyDocument.productName)).toBeTruthy()
    const uploadCall = fetchImpl.mock.calls.find(([url]) => url === '/api/documents')
    expect(uploadCall[1].body.get('file').name).toBe('테스트약관.pdf')
    const analysisCall = fetchImpl.mock.calls.find(([url]) => url === '/api/analyses')
    expect(JSON.parse(analysisCall[1].body)).toEqual({ documentId: 'test-document', riderIds: [], viewAll: false })
    expect(fetchImpl.mock.calls.every(([url]) => url.startsWith('/api/'))).toBe(true)
  })
})
