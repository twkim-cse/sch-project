import { describe, expect, it, vi } from 'vitest'
import {
  createAnalysisService,
  readCurrentAnalysisId,
  rememberAnalysisId,
} from './analysisService.js'

const NOW = Date.parse('2030-01-01T00:00:00Z')
const validAnalysis = {
  analysisId: 'analysis-1',
  status: 'DONE',
  expiresAt: '2030-01-02T00:00:00Z',
  deletedAt: null,
}

function makeService({ analysis = validAnalysis, result = { summary: 'test' }, id = 'analysis-1', now = () => NOW } = {}) {
  const backend = {
    getAnalysis: vi.fn().mockResolvedValue(analysis),
    getResult: vi.fn().mockResolvedValue(result),
  }
  return {
    backend,
    service: createAnalysisService({ backend, getCurrentAnalysisId: () => id, now }),
  }
}

describe('completed analysis eligibility', () => {
  it('starts without a result and makes no backend request when disconnected', async () => {
    const getCurrentAnalysisId = vi.fn()
    const service = createAnalysisService({ getCurrentAnalysisId })
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(getCurrentAnalysisId).not.toHaveBeenCalled()
  })

  it('accepts a matching, unexpired DONE analysis with a real result', async () => {
    const { service, backend } = makeService()
    expect(await service.getCompletedAnalysis()).toEqual({
      analysis: validAnalysis,
      result: { summary: 'test' },
    })
    expect(backend.getAnalysis).toHaveBeenCalledWith('analysis-1')
    expect(backend.getResult).toHaveBeenCalledWith('analysis-1')
  })

  it.each(['QUEUED', 'RUNNING', 'FAILED', 'done', undefined])('rejects status %s', async (status) => {
    const { service, backend } = makeService({ analysis: { ...validAnalysis, status } })
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(backend.getResult).not.toHaveBeenCalled()
  })

  it.each([
    null,
    { ...validAnalysis, analysisId: 'another-analysis' },
    { ...validAnalysis, expiresAt: '2029-12-31T23:59:59Z' },
    { ...validAnalysis, expiresAt: '2030-01-01T00:00:00Z' },
    { ...validAnalysis, expiresAt: undefined },
    { ...validAnalysis, expiresAt: 'invalid' },
    { ...validAnalysis, deletedAt: '2029-12-31T00:00:00Z' },
    { ...validAnalysis, deleted: true },
  ])('rejects deleted, mismatched, expired or unverifiable metadata %#', async (analysis) => {
    const { service, backend } = makeService({ analysis })
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(backend.getResult).not.toHaveBeenCalled()
  })

  it.each([null, {}, [], 'missing'])('rejects a missing or invalid result %#', async (result) => {
    const { service } = makeService({ result })
    expect(await service.getCompletedAnalysis()).toBeNull()
  })

  it.each([null, '', '   ', -1, {}])('does not request an invalid analysisId %#', async (id) => {
    const { service, backend } = makeService({ id })
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(backend.getAnalysis).not.toHaveBeenCalled()
  })

  it('supports numeric Spring analysis identifiers', async () => {
    const { service, backend } = makeService({
      id: 123,
      analysis: { ...validAnalysis, analysisId: 123 },
    })
    expect(await service.getCompletedAnalysis()).not.toBeNull()
    expect(backend.getResult).toHaveBeenCalledWith('123')
  })

  it('rechecks retention after retrieving the result', async () => {
    const now = vi.fn().mockReturnValueOnce(NOW).mockReturnValue(NOW + 86400000)
    const { service } = makeService({ now })
    expect(await service.getCompletedAnalysis()).toBeNull()
  })

  it('checks fresh metadata on each access rather than trusting a cached flag', async () => {
    const { service, backend } = makeService()
    expect(await service.getCompletedAnalysis()).not.toBeNull()
    backend.getAnalysis.mockResolvedValue(null)
    expect(await service.getCompletedAnalysis()).toBeNull()
    expect(backend.getAnalysis).toHaveBeenCalledTimes(2)
  })

  it('preserves connection errors so callers can distinguish failure from absence', async () => {
    const { service, backend } = makeService()
    backend.getAnalysis.mockRejectedValue(new Error('network unavailable'))
    await expect(service.getCompletedAnalysis()).rejects.toThrow('network unavailable')
  })

  it('stores only the analysis reference and handles unavailable storage', () => {
    expect(readCurrentAnalysisId()).toBeNull()
    expect(rememberAnalysisId(' analysis-1 ')).toBe(true)
    expect(readCurrentAnalysisId()).toBe('analysis-1')
    expect(rememberAnalysisId('')).toBe(false)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(readCurrentAnalysisId()).toBeNull()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    expect(rememberAnalysisId('analysis-2')).toBe(false)
  })
})
