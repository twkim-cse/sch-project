import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import App from './App.jsx'
import { createAnalysisService } from './services/analysisService.js'

function RouteObserver() {
  const location = useLocation()
  return (
    <output aria-label="현재 경로" data-analysis-id={location.state?.analysisId}>
      {location.pathname}
    </output>
  )
}

function renderPage({ path = '/home', service } = {}) {
  window.history.replaceState(null, '', path)
  const user = userEvent.setup()
  render(
    <BrowserRouter>
      <App analysisService={service} />
      <RouteObserver />
    </BrowserRouter>,
  )
  return user
}

function expectPath(path) {
  expect(window.location.pathname).toBe(path)
  expect(screen.getByLabelText('현재 경로').textContent).toBe(path)
}

describe('home and shared navigation', () => {
  it('renders the six requested check items and their descriptions', () => {
    renderPage({ path: '/check-items' })
    const names = [
      '면책 사유', '대기 기간', '갱신 조건',
      '알릴 의무', '약관상 질병·수술의 정의', '중도 해지 및 해지환급금',
    ]
    for (const name of names) {
      const section = screen.getByRole('region', { name })
      expect(within(section).getByRole('heading', { name })).toBeTruthy()
      expect(section.querySelectorAll('p').length).toBe(2)
    }
    expect(screen.getAllByRole('region')).toHaveLength(6)
  })

  it('renders the four guide steps separately from the local illustration', () => {
    renderPage({ path: '/guide' })
    const steps = within(screen.getByRole('list', { name: '서비스 이용 순서' })).getAllByRole('listitem')
    expect(steps).toHaveLength(4)
    expect(within(steps[0]).getByRole('heading', { name: '보험약관 PDF 업로드' })).toBeTruthy()
    expect(within(steps[3]).getByRole('heading', { name: 'AI 분석 결과 확인' })).toBeTruthy()
    expect(screen.getByRole('img', { name: '파란 도움 아이콘이 있는 열린 안내서' }).getAttribute('src')).not.toContain('https://')
  })

  it('renders all four independent local illustrations and the full content', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '내 보험약관 쉽게 알아보세요' })).toBeTruthy()
    expect(screen.getAllByRole('region')).toHaveLength(3)
    expect(screen.getAllByRole('img')).toHaveLength(5)
    expect(screen.getAllByRole('img').every((image) => !image.src.includes('figma.com'))).toBe(true)
    expect(screen.getByRole('region', { name: '이용 가이드' })).toBeTruthy()
  })

  it('preserves Frame 46 and its exact home label, and supports logo navigation', async () => {
    const user = renderPage()
    await user.click(screen.getByRole('link', { name: '들다 시작 화면' }))
    expectPath('/')
    expect(screen.getByRole('heading', { name: /내가 가입한 보험/ })).toBeTruthy()
    expect(screen.queryByRole('navigation')).toBeNull()
    expect(screen.getByRole('img', { name: '보험 서류를 읽으며 고민하는 남성' })).toBeTruthy()
    await user.click(screen.getByRole('link', { name: '홈으로' }))
    expectPath('/home')
    expect(screen.getByRole('link', { name: '홈으로 가기' })).toBeTruthy()
  })

  it.each([
    ['점검 항목', '/check-items', '복잡한 보험약관 핵심만 확인하세요'],
    ['이용 가이드', '/guide', '이용 가이드'],
  ])('connects the %s header destination', async (label, path, title) => {
    const user = renderPage()
    await user.click(within(screen.getByRole('navigation')).getByRole('link', { name: label }))
    expectPath(path)
    expect(screen.getByRole('heading', { level: 1, name: title })).toBeTruthy()
    await user.click(screen.getByRole('link', { name: '홈으로 가기' }))
    expectPath('/home')
  })

  it.each([
    ['핵심 점검 항목', '/check-items'],
    ['이용 가이드', '/guide'],
  ])('connects the %s lower learn-more link', async (sectionName, path) => {
    const user = renderPage()
    const section = screen.getByRole('region', { name: sectionName })
    await user.click(within(section).getByRole('link', { name: '더 알아보기' }))
    expectPath(path)
  })

  it.each(['/home', '/check-items', '/guide'])('connects the upload button from %s', async (path) => {
    const user = renderPage({ path })
    await user.click(screen.getByRole('link', { name: path === '/check-items' ? '약관 분석하러 가기' : '약관 분석하기' }))
    expectPath('/upload')
    expect(screen.getByRole('heading', { level: 1, name: '보험약관 분석하기' })).toBeTruthy()
  })

  it.each(['/upload', '/results', '/check-items', '/guide'])('keeps the shared logo and home links on %s', async (path) => {
    const user = renderPage({ path })
    await user.click(screen.getByRole('link', { name: '홈으로 가기' }))
    expectPath('/home')
    await user.click(screen.getByRole('link', { name: '들다 시작 화면' }))
    expectPath('/')
  })

  it('supports browser back and forward after client-side navigation', async () => {
    const user = renderPage()
    await user.click(screen.getByRole('link', { name: '점검 항목' }))
    await user.click(screen.getByRole('link', { name: '약관 분석하러 가기' }))
    expectPath('/upload')
    act(() => window.history.back())
    await waitFor(() => expectPath('/check-items'))
    act(() => window.history.back())
    await waitFor(() => expectPath('/home'))
    act(() => window.history.forward())
    await waitFor(() => expectPath('/check-items'))
  })
})

describe('conditional result access and Frame 51', () => {
  it('does not reopen an old modal after browser back and forward', async () => {
    const user = renderPage({ path: '/guide' })
    await user.click(screen.getByRole('link', { name: '홈으로 가기' }))
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    act(() => window.history.back())
    await waitFor(() => expectPath('/guide'))
    expect(screen.queryByRole('dialog')).toBeNull()
    act(() => window.history.forward())
    await waitFor(() => expectPath('/home'))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.body.style.overflow).not.toBe('hidden')
  })

  it('ignores a pending lookup even if history returns to its original entry', async () => {
    let finish
    const service = { getCompletedAnalysis: vi.fn(() => new Promise((resolve) => { finish = resolve })) }
    const user = renderPage({ service })
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    await user.click(screen.getByRole('link', { name: '이용 가이드' }))
    act(() => window.history.back())
    await waitFor(() => expectPath('/home'))
    await act(async () => finish({ analysis: { analysisId: 'old-analysis' }, result: { summary: 'test' } }))
    expectPath('/home')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('opens and closes over the current page without changing URL or scroll position', async () => {
    const user = renderPage()
    window.scrollY = 780
    document.body.style.overflow = 'auto'
    document.body.style.paddingRight = '3px'
    const trigger = screen.getByRole('button', { name: '결과 확인' })
    await user.click(trigger)
    const dialog = screen.getByRole('dialog', { name: '결과를 확인할 수 없습니다' })
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expectPath('/home')
    expect(window.scrollY).toBe(780)
    expect(document.body.style.overflow).toBe('hidden')
    const close = within(dialog).getByRole('button', { name: '닫기' })
    expect(document.activeElement).toBe(close)
    await user.click(close)
    expect(screen.queryByRole('dialog')).toBeNull()
    expectPath('/home')
    expect(window.scrollY).toBe(780)
    expect(window.scrollTo).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(trigger)
    expect(document.body.style.overflow).toBe('auto')
    expect(document.body.style.paddingRight).toBe('3px')
  })

  it('handles the native Escape cancel event and stays on the guide page', async () => {
    const user = renderPage({ path: '/guide' })
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expectPath('/guide')
  })

  it('uses the same inspection function for the header and lower result button', async () => {
    const service = { getCompletedAnalysis: vi.fn().mockResolvedValue(null) }
    const user = renderPage({ service })
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    await user.click(screen.getByRole('button', { name: '닫기' }))
    const section = screen.getByRole('region', { name: '분석 결과 확인' })
    await user.click(within(section).getByRole('button', { name: '더 알아보기' }))
    expect(screen.getByRole('dialog', { name: '결과를 확인할 수 없습니다' })).toBeTruthy()
    expect(service.getCompletedAnalysis).toHaveBeenCalledTimes(2)
    expectPath('/home')
  })

  it.each(['header', 'lower'])('routes valid DONE results from the %s action', async (action) => {
    const service = createAnalysisService({
      getCurrentAnalysisId: () => 'analysis-1',
      now: () => Date.parse('2030-01-01T00:00:00Z'),
      backend: {
        getAnalysis: async () => ({
          analysisId: 'analysis-1', status: 'DONE',
          expiresAt: '2030-01-02T00:00:00Z', deletedAt: null,
        }),
        getResult: async () => ({ summary: 'test-only result' }),
      },
    })
    const user = renderPage({ service })
    const trigger = action === 'header'
      ? screen.getByRole('button', { name: '결과 확인' })
      : within(screen.getByRole('region', { name: '분석 결과 확인' })).getByRole('button', { name: '더 알아보기' })
    await user.click(trigger)
    await waitFor(() => expectPath('/results'))
    expect(screen.getByLabelText('현재 경로').dataset.analysisId).toBe('analysis-1')
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('shows connection failures separately from missing results', async () => {
    const service = { getCompletedAnalysis: vi.fn().mockRejectedValue(new Error('offline')) }
    const user = renderPage({ service })
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    expect(screen.getByRole('dialog', { name: '분석 결과를 확인할 수 없습니다' })).toBeTruthy()
    expectPath('/home')
  })

  it('prevents duplicate checks while an inspection is pending', async () => {
    let finish
    const service = { getCompletedAnalysis: vi.fn(() => new Promise((resolve) => { finish = resolve })) }
    const user = renderPage({ service })
    const trigger = screen.getByRole('button', { name: '결과 확인' })
    await user.click(trigger)
    expect(trigger.disabled).toBe(true)
    await user.click(trigger)
    expect(service.getCompletedAnalysis).toHaveBeenCalledTimes(1)
    await act(async () => finish(null))
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(trigger.disabled).toBe(false)
  })

  it('ignores a late result if the user has navigated to another page', async () => {
    let finish
    const service = { getCompletedAnalysis: vi.fn(() => new Promise((resolve) => { finish = resolve })) }
    const user = renderPage({ service })
    await user.click(screen.getByRole('button', { name: '결과 확인' }))
    await user.click(screen.getByRole('link', { name: '이용 가이드' }))
    await act(async () => finish({ analysis: { analysisId: 'analysis-1' }, result: { summary: 'test' } }))
    expectPath('/guide')
    expect(screen.queryByRole('dialog')).toBeNull()
  })
})
