import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSpringApi } from './springApi.js'
import { API_TIMEOUT_MS } from '../config/analysis.js'

function response(data, status = 200) {
  return { ok: status >= 200 && status < 300, status, text: async () => typeof data === 'string' ? data : JSON.stringify(data) }
}

afterEach(() => vi.useRealTimers())

describe('Spring HTTP client', () => {
  it('deletes the selected document and accepts an empty 204 response', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response('', 204))
    const api = createSpringApi({ baseUrl: 'http://localhost:8080/', fetchImpl })
    await expect(api.deleteDocument('doc/with space')).resolves.toBeNull()
    const [url, options] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/api/documents/doc%2Fwith%20space')
    expect(options.method).toBe('DELETE')
    expect(options.body).toBeUndefined()
    expect(options.expectNoContent).toBeUndefined()
    await expect(api.getDocument('doc')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it.each([['', 200], [{ deleted: true }, 200], ['<html>Vite fallback</html>', 200]])('does not clear results for an unacknowledged deletion %#', async (body, status) => {
    const api = createSpringApi({ fetchImpl: vi.fn().mockResolvedValue(response(body, status)) })
    await expect(api.deleteDocument('doc')).rejects.toBeInstanceOf(Error)
  })
  it('preserves the deletion failure reason and status', async () => {
    const api = createSpringApi({ fetchImpl: vi.fn().mockResolvedValue(response({ code: 'DELETE_FAILED', message: '문서 삭제에 실패했습니다.' }, 503)) })
    await expect(api.deleteDocument('doc')).rejects.toMatchObject({ status: 503, code: 'DELETE_FAILED', message: '문서 삭제에 실패했습니다.' })
  })
  it('uploads the selected file as multipart without setting the boundary manually', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response({ documentId: 'doc', status: 'CHECKING' }, 202))
    const api = createSpringApi({ baseUrl: 'http://localhost:8080/', fetchImpl })
    const file = new File(['%PDF-1.7 test'], 'test.pdf', { type: 'application/pdf' })
    await api.uploadDocument(file)
    const [url, options] = fetchImpl.mock.calls[0]
    expect(url).toBe('http://localhost:8080/api/documents')
    expect(options.method).toBe('POST')
    expect(options.body.get('file')).toBe(file)
    expect(options.headers['Content-Type']).toBeUndefined()
  })
  it('sends viewAll with no individual rider IDs and uses the documented URLs', async () => {
    const fetchImpl = vi.fn().mockResolvedValue(response({ ok: true }))
    const api = createSpringApi({ fetchImpl, baseUrl: '' })
    await api.startAnalysis({ documentId: 'doc', riderIds: ['rider'], viewAll: true })
    expect(JSON.parse(fetchImpl.mock.calls[0][1].body)).toEqual({ documentId: 'doc', riderIds: [], viewAll: true })
    await api.getDocument('doc')
    await api.getAnalysis('analysis')
    await api.getResult('analysis')
    await api.getClause('doc', 'rider/art 5')
    expect(fetchImpl.mock.calls.map(([url]) => url)).toEqual(['/api/analyses', '/api/documents/doc', '/api/analyses/analysis', '/api/analyses/analysis/result', '/api/documents/doc/clauses/rider%2Fart%205'])
  })
  it.each([[400, 'NOT_PDF'], [413, 'FILE_TOO_LARGE'], [404, 'NOT_FOUND'], [409, 'NOT_READY']])('retains HTTP %s and the server code', async (status, code) => {
    const api = createSpringApi({ fetchImpl: vi.fn().mockResolvedValue(response({ code, message: 'server explanation' }, status)) })
    await expect(api.getDocument('doc')).rejects.toMatchObject({ status, code, message: 'server explanation' })
  })
  it.each(['<html>Vite index</html>', ''])('does not treat an unconnected dev server as a successful API %#', async (body) => {
    const api = createSpringApi({ fetchImpl: vi.fn().mockResolvedValue(response(body, 404)) })
    await expect(api.getDocument('doc')).rejects.toMatchObject({ code: 'BACKEND_UNAVAILABLE' })
  })
  it('normalizes connection failures and malformed error fields for display', async () => {
    const api = createSpringApi({ fetchImpl: vi.fn().mockRejectedValue(new TypeError('Failed to fetch')) })
    await expect(api.getAnalysis('analysis')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
    const malformed = createSpringApi({ fetchImpl: vi.fn().mockResolvedValue(response({ code: {}, message: {} }, 500)) })
    await expect(malformed.getAnalysis('analysis')).rejects.toMatchObject({ code: 'HTTP_500', message: '서버 요청을 처리하지 못했습니다.' })
  })
  it('aborts timed-out requests and distinguishes user cancellation', async () => {
    vi.useFakeTimers()
    const fetchImpl = vi.fn((url, options) => new Promise((resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
    }))
    const api = createSpringApi({ fetchImpl })
    const timeoutPromise = expect(api.getDocument('doc')).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT' })
    await vi.advanceTimersByTimeAsync(API_TIMEOUT_MS)
    await timeoutPromise
    const controller = new AbortController()
    const abortPromise = expect(api.getDocument('doc', { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
    controller.abort()
    await abortPromise
  })
})
