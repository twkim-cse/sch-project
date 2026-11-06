import { API_TIMEOUT_MS, UPLOAD_TIMEOUT_MS } from '../config/analysis.js'

export class ApiError extends Error {
  constructor(code, message, { status = 0, details = null } = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }
}

export function createSpringApi({
  baseUrl = import.meta.env.VITE_API_BASE_URL || '',
  fetchImpl,
} = {}) {
  const base = baseUrl.replace(/\/+$/, '')

  async function request(path, { signal, timeout = API_TIMEOUT_MS, expectNoContent = false, ...options } = {}) {
    const controller = new AbortController()
    let timedOut = false
    const abort = () => controller.abort()
    if (signal?.aborted) controller.abort()
    else signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, timeout)

    try {
      if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError')
      const response = await (fetchImpl || globalThis.fetch)(`${base}${path}`, {
        ...options,
        headers: { Accept: 'application/json', ...options.headers },
        signal: controller.signal,
      })
      if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError')
      if (expectNoContent && response.status === 204) return null
      const text = await response.text()
      let data = null
      if (text) {
        try { data = JSON.parse(text) } catch {
          throw new ApiError('BACKEND_UNAVAILABLE', '백엔드 API에 연결되지 않았습니다. 연결 설정을 확인해 주세요.', { status: response.status })
        }
      }
      if (!response.ok) {
        if (!data) throw new ApiError('BACKEND_UNAVAILABLE', '백엔드 API 연결 대기 상태입니다. 서버 연결 설정을 확인해 주세요.', { status: response.status })
        const error = data?.error || data
        throw new ApiError(typeof error?.code === 'string' ? error.code : `HTTP_${response.status}`, typeof error?.message === 'string' ? error.message : '서버 요청을 처리하지 못했습니다.', { status: response.status, details: data })
      }
      if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError')
      if (expectNoContent) {
        throw new ApiError('INVALID_RESPONSE', '삭제 완료 응답(204)을 확인하지 못했습니다. 서버 상태를 확인한 후 다시 시도해 주세요.', { status: response.status })
      }
      if (!data || typeof data !== 'object' || Array.isArray(data)) {
        throw new ApiError('INVALID_RESPONSE', '서버 응답 형식을 확인할 수 없습니다.')
      }
      return data
    } catch (error) {
      if (signal?.aborted) throw new DOMException('Request aborted', 'AbortError')
      if (timedOut) throw new ApiError('REQUEST_TIMEOUT', '서버 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.')
      if (error instanceof ApiError) throw error
      throw new ApiError('NETWORK_ERROR', '서버에 연결할 수 없습니다. 백엔드 연결 상태를 확인해 주세요.')
    } finally {
      clearTimeout(timer)
      signal?.removeEventListener('abort', abort)
    }
  }

  const id = (value) => encodeURIComponent(String(value))
  return {
    uploadDocument(file, options) {
      const body = new FormData()
      body.append('file', file)
      return request('/api/documents', { ...options, method: 'POST', body, timeout: UPLOAD_TIMEOUT_MS })
    },
    getDocument(documentId, options) {
      return request(`/api/documents/${id(documentId)}`, options)
    },
    deleteDocument(documentId, options) {
      return request(`/api/documents/${id(documentId)}`, { ...options, method: 'DELETE', expectNoContent: true })
    },
    startAnalysis({ documentId, riderIds, viewAll }, options) {
      return request('/api/analyses', {
        ...options,
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ documentId, riderIds: viewAll ? [] : riderIds, viewAll }),
      })
    },
    getAnalysis(analysisId, options) {
      return request(`/api/analyses/${id(analysisId)}`, options)
    },
    getResult(analysisId, options) {
      return request(`/api/analyses/${id(analysisId)}/result`, options)
    },
    getClause(documentId, clauseId, options) {
      return request(`/api/documents/${id(documentId)}/clauses/${id(clauseId)}`, options)
    },
  }
}

export const springApi = createSpringApi()
