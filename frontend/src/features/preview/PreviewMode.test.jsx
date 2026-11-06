import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrowserRouter, useNavigate } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../../App.jsx'
import { rememberAnalysisId } from '../../services/analysisService.js'
import { withPreview } from './previewRouting.js'

function RawNavigation() {
  const navigate = useNavigate()
  return <button type="button" onClick={() => navigate('/upload')}>일반 주소로 접속 테스트</button>
}

function setup(path = '/?preview=1', { stored = false } = {}) {
  const fetch = vi.fn().mockRejectedValue(new Error('Unexpected real network request'))
  vi.stubGlobal('fetch', fetch)
  const api = Object.fromEntries(['uploadDocument', 'getDocument', 'startAnalysis', 'getAnalysis', 'getResult', 'getClause', 'deleteDocument'].map((key) => [key, vi.fn().mockRejectedValue(new Error('Unexpected live API call'))]))
  const service = { getCompletedAnalysis: vi.fn().mockResolvedValue(null) }
  if (stored) rememberAnalysisId('real-analysis', { documentId: 'real-document', expiresAt: '2099-01-01T00:00:00Z' })
  window.history.replaceState(null, '', path)
  render(<BrowserRouter><App api={api} analysisService={service} /><RawNavigation /></BrowserRouter>)
  return { user: userEvent.setup(), api, fetch, service }
}

function expectNoNetwork(env) {
  expect(env.fetch).not.toHaveBeenCalled()
  for (const request of Object.values(env.api)) expect(request).not.toHaveBeenCalled()
  expect(env.service.getCompletedAnalysis).not.toHaveBeenCalled()
}

async function openMenu(user, label) {
  const scope = document.querySelector('dialog[open] .preview-navigation') || document.querySelector('.preview-navigation--global')
  const menu = within(scope)
  const toggle = menu.getByRole('button', { name: '개발자 미리보기' })
  if (toggle.getAttribute('aria-expanded') !== 'true') await user.click(toggle)
  await user.click(menu.getByRole('button', { name: label, exact: true }))
}

afterEach(() => vi.unstubAllEnvs())

describe('preview activation, isolation and routing', () => {
  it.each(['/', '/upload', '/?preview=0', '/upload?preview=true'])('keeps the normal service at %s without developer controls', (path) => {
    setup(path)
    expect(screen.queryByRole('button', { name: '개발자 미리보기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '샘플 약관으로 테스트' })).toBeNull()
    expect(screen.getByRole('link', { name: /^홈으로/ }).getAttribute('href')).toBe('/home')
  })

  it('ignores preview query parameters outside the Vite development environment', () => {
    vi.stubEnv('DEV', false)
    setup('/upload?preview=1&previewFrame=27')
    expect(screen.getByRole('heading', { name: '보험약관 분석하기' })).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.queryByRole('button', { name: '개발자 미리보기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '샘플 약관으로 테스트' })).toBeNull()
  })

  it('preserves preview on Header and CTA navigation, history back, and exits immediately on a normal URL', async () => {
    const env = setup('/?preview=1', { stored: true })
    const { user } = env
    await screen.findByRole('button', { name: '개발자 미리보기' })
    const reference = sessionStorage.getItem('deulda.analysisReference')
    await user.click(screen.getByRole('link', { name: '홈으로' }))
    expect(window.location.pathname).toBe('/home')
    expect(window.location.search).toBe('?preview=1')
    await user.click(screen.getByRole('link', { name: '점검 항목', exact: true }))
    expect(window.location.pathname).toBe('/check-items')
    await user.click(screen.getByRole('link', { name: '약관 분석하러 가기' }))
    expect(window.location.pathname).toBe('/upload')
    expect(screen.getByRole('button', { name: '샘플 약관으로 테스트' })).toBeTruthy()
    await act(async () => window.history.back())
    await waitFor(() => expect(window.location.pathname).toBe('/check-items'))
    expect(window.location.search).toBe('?preview=1')
    await user.click(screen.getByRole('link', { name: '들다 시작 화면' }))
    expect(window.location.pathname).toBe('/')
    expect(window.location.search).toBe('?preview=1')
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(reference)
    expectNoNetwork(env)
    await user.click(screen.getByRole('button', { name: '일반 주소로 접속 테스트' }))
    expect(window.location.search).toBe('')
    expect(screen.queryByRole('button', { name: '개발자 미리보기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '샘플 약관으로 테스트' })).toBeNull()
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(reference)
  })

  it('merges preview routing into string/object URLs without losing query, hash or history deltas', () => {
    expect(withPreview('/guide?tab=2#steps', true)).toBe('/guide?tab=2&preview=1#steps')
    expect(withPreview({ pathname: '/upload', search: '?previewFrame=26', hash: '#section' }, true)).toEqual({ pathname: '/upload', search: '?previewFrame=26&preview=1', hash: '#section' })
    expect(withPreview('/results?preview=1', true)).toBe('/results?preview=1')
    expect(withPreview(-1, true)).toBe(-1)
    expect(withPreview('https://example.com', true)).toBe('https://example.com')
    expect(withPreview('/upload?tab=1', false)).toBe('/upload?tab=1')
  })
})

describe('developer menu direct page and overlay entries', () => {
  it.each([
    ['Frame 46 - 시작 화면', '/', null], ['Desktop-3 - 홈 화면', '/home', null],
    ['Desktop-5 - 점검 항목', '/check-items', null], ['Frame 52 - 이용 가이드', '/guide', null],
    ['Frame 17 - PDF 업로드', '/upload', null], ['Frame 45 - 분석 결과', '/results', null],
    ['Frame 24 - 문서 확인 중', '/upload', '문서 확인중 ...'],
    ['Frame 25 - 문서 확인 완료', '/upload', '보험 약관 확인 완료'],
    ['Frame 26 - 가입한 특약 선택', '/upload', '가입한 특약 선택'],
    ['Frame 27 - AI 분석 진행', '/upload', 'AI가 보험약관을 분석하고 있습니다'],
    ['Frame 49 - 원문 보기', '/results', '원문 보기'],
    ['Frame 50 - 파일 오류', '/upload', '파일을 확인할 수 없습니다'],
    ['Frame 51 - 분석 결과 없음', '/home', '결과를 확인할 수 없습니다'],
    ['Frame 53 - 분석 데이터 삭제 확인', '/results', '분석 데이터를 삭제하시겠습니까?'],
  ])('opens %s over the appropriate background', async (label, pathname, title) => {
    const env = setup()
    await screen.findByRole('button', { name: '개발자 미리보기' })
    await openMenu(env.user, label)
    await waitFor(() => expect(window.location.pathname).toBe(pathname))
    expect(new URLSearchParams(window.location.search).get('preview')).toBe('1')
    const background = screen.getByRole('main')
    if (title) {
      const modal = await screen.findByRole('dialog', { name: title })
      expect(background).not.toBeNull()
      await env.user.click(within(modal).getByRole('button', { name: '닫기' }))
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(screen.getByRole('main')).toBe(background)
    }
    expectNoNetwork(env)
  })

  it('can navigate to another frame using the menu inside an active native overlay', async () => {
    const env = setup('/upload?preview=1&previewFrame=24')
    await screen.findByRole('dialog')
    await openMenu(env.user, 'Frame 53 - 분석 데이터 삭제 확인')
    const modal = await screen.findByRole('dialog', { name: '분석 데이터를 삭제하시겠습니까?' })
    expect(window.location.pathname).toBe('/results')
    await env.user.click(within(modal).getByRole('button', { name: '삭제하기' }))
    await waitFor(() => expect(window.location.pathname).toBe('/upload'))
    expect(screen.queryByRole('dialog')).toBeNull()
    expectNoNetwork(env)
  })

  it('collapses and expands the developer menu without changing the current screen', async () => {
    const env = setup()
    const toggle = await screen.findByRole('button', { name: '개발자 미리보기' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(screen.queryByRole('navigation', { name: '미리보기 화면 선택' })).toBeNull()
    await env.user.click(toggle)
    expect(screen.getByRole('navigation', { name: '미리보기 화면 선택' })).toBeTruthy()
    await env.user.click(toggle)
    expect(screen.queryByRole('navigation', { name: '미리보기 화면 선택' })).toBeNull()
    expect(window.location.pathname).toBe('/')
  })
})

describe('mock upload and analysis flow', () => {
  it('runs Frame 17–27 with selected riders, reversible manual progress, results, retained completion and local deletion', async () => {
    const env = setup('/upload?preview=1', { stored: true })
    const { user } = env
    const sample = await screen.findByRole('button', { name: '샘플 약관으로 테스트' })
    const stored = sessionStorage.getItem('deulda.analysisReference')
    await user.click(sample)
    let modal = screen.getByRole('dialog', { name: '문서 확인중 ...' })
    await user.click(within(modal).getByRole('button', { name: '문서 확인 완료' }))
    modal = screen.getByRole('dialog', { name: '보험 약관 확인 완료' })
    expect(within(modal).getByText('(예시) 종합건강보험')).toBeTruthy()
    expect(within(modal).getByText('2025년 4월')).toBeTruthy()
    expect(within(modal).getByText('212쪽')).toBeTruthy()
    expect(within(modal).getAllByRole('listitem')).toHaveLength(4)
    await user.click(within(modal).getByRole('button', { name: '확인하고 다음으로' }))
    modal = screen.getByRole('dialog', { name: '가입한 특약 선택' })
    const first = within(modal).getByRole('checkbox', { name: /암진단 특별약관/ })
    await user.click(first)
    await user.click(within(modal).getByRole('checkbox', { name: /수술비 특별약관/ }))
    expect(first.checked).toBe(true)
    const all = within(modal).getByRole('checkbox', { name: '전체 보기' })
    await user.click(all)
    expect(first.disabled).toBe(true)
    expect(within(modal).getByText('선택한 특약 4개 · 전체 보기')).toBeTruthy()
    await user.click(all)
    expect(first.disabled).toBe(false)
    expect(first.checked).toBe(true)
    expect(within(modal).getByLabelText('주계약 항상 포함')).toBeTruthy()
    await user.click(within(modal).getByRole('button', { name: '분석 시작하기' }))
    modal = screen.getByRole('dialog', { name: 'AI가 보험약관을 분석하고 있습니다' })
    expect(within(modal).getByRole('progressbar').getAttribute('aria-valuenow')).toBe('0')
    expect(within(modal).getByRole('button', { name: '결과 확인하기' }).disabled).toBe(true)
    expect(within(modal).getAllByText('진행 중')).toHaveLength(1)
    expect(within(modal).getAllByText('대기 중')).toHaveLength(4)
    await user.click(within(modal).getByRole('button', { name: '3. 원문 인용 대조' }))
    expect(within(modal).getByRole('progressbar').getAttribute('aria-valuenow')).toBe('40')
    expect(within(modal).getAllByText('완료')).toHaveLength(2)
    await user.click(within(modal).getByRole('button', { name: '1. 약관 텍스트 추출' }))
    expect(within(modal).getByRole('progressbar').getAttribute('aria-valuenow')).toBe('0')
    for (let index = 1; index <= 5; index += 1) {
      await user.click(within(modal).getByRole('button', { name: '다음 단계' }))
      expect(within(modal).getByRole('progressbar').getAttribute('aria-valuenow')).toBe(String(index * 20))
    }
    expect(within(modal).getAllByText('완료')).toHaveLength(5)
    expect(within(modal).getByRole('button', { name: '결과 확인하기' }).disabled).toBe(false)
    await user.click(within(modal).getByRole('button', { name: '결과 확인하기' }))
    await screen.findByRole('link', { name: '확인 완료' })
    expect(window.location.pathname).toBe('/results')
    expect([...document.querySelectorAll('.result-counter strong')].map((node) => node.textContent)).toEqual(['3', '2', '2'])
    await user.click(screen.getByRole('link', { name: '확인 완료' }))
    expect(window.location.pathname).toBe('/upload')
    expect(window.location.search).toBe('?preview=1')
    await user.click(screen.getByRole('button', { name: '결과 확인', exact: true }))
    await screen.findByRole('link', { name: '확인 완료' })
    await user.click(screen.getAllByRole('button', { name: '원문 보기' })[2])
    modal = screen.getByRole('dialog')
    await waitFor(() => expect(modal.querySelector('mark')).not.toBeNull())
    expect(within(modal).queryByRole('heading', { name: 'AI 쉬운 해설' })).toBeNull()
    await user.click(within(modal).getByRole('button', { name: '닫기' }))
    await user.click(screen.getByRole('button', { name: '분석결과 삭제하기' }))
    modal = screen.getByRole('dialog')
    await user.click(within(modal).getByRole('button', { name: '삭제하기' }))
    await waitFor(() => expect(window.location.pathname).toBe('/upload'))
    await user.click(screen.getByRole('button', { name: '결과 확인', exact: true }))
    modal = await screen.findByRole('dialog', { name: '결과를 확인할 수 없습니다' })
    expect(within(modal).getByText('아직 분석한 약관이 없습니다')).toBeTruthy()
    expect(sessionStorage.getItem('deulda.analysisReference')).toBe(stored)
    expect(sessionStorage.getItem('deulda.analysisId')).toBe('real-analysis')
    expectNoNetwork(env)
  })

  it('can select a real local PDF and start its mock checking without transmitting it', async () => {
    const env = setup('/upload?preview=1')
    await screen.findByRole('button', { name: '샘플 약관으로 테스트' })
    const file = new File(['%PDF-1.7 local-only'], '로컬약관.pdf', { type: 'application/pdf' })
    await env.user.upload(screen.getByLabelText('보험약관 PDF 파일 선택'), file)
    expect(screen.getByText('로컬약관.pdf')).toBeTruthy()
    await env.user.click(screen.getByRole('button', { name: '업로드 시작하기' }))
    expect(screen.getByRole('dialog', { name: '문서 확인중 ...' })).toBeTruthy()
    expectNoNetwork(env)
  })

  it('shows local format validation and permits selecting all riders for the mock result', async () => {
    const env = setup('/upload?preview=1')
    await screen.findByRole('button', { name: '샘플 약관으로 테스트' })
    fireEvent.change(screen.getByLabelText('보험약관 PDF 파일 선택'), { target: { files: [new File(['txt'], 'bad.txt', { type: 'text/plain' })] } })
    let modal = screen.getByRole('dialog')
    expect(within(modal).getByRole('alert').textContent).toBe('PDF 파일만 업로드할 수 있습니다.')
    await env.user.click(within(modal).getByRole('button', { name: '닫기' }))
    await openMenu(env.user, 'Frame 26 - 가입한 특약 선택')
    modal = screen.getByRole('dialog')
    await env.user.click(within(modal).getByRole('checkbox', { name: '전체 보기' }))
    await env.user.click(within(modal).getByRole('button', { name: '분석 시작하기' }))
    modal = screen.getByRole('dialog')
    await env.user.click(within(modal).getByRole('button', { name: '모든 단계 완료' }))
    await env.user.click(within(modal).getByRole('button', { name: '결과 확인하기' }))
    await screen.findByRole('link', { name: '확인 완료' })
    expect([...document.querySelectorAll('.result-counter strong')].map((node) => node.textContent)).toEqual(['3', '2', '4'])
    expect(screen.getByText('공통 (주계약) · 모든 특약')).toBeTruthy()
    expectNoNetwork(env)
  })

  it('switches all four Frame 50 error examples without making an API request', async () => {
    const env = setup('/upload?preview=1&previewFrame=50')
    const modal = await screen.findByRole('dialog')
    const select = within(modal).getByLabelText('테스트 오류 선택')
    for (const [code, copy] of [
      ['NOT_PDF', 'PDF 파일만 업로드할 수 있습니다.'],
      ['FILE_TOO_LARGE', '파일 용량이 초과되었습니다. 50MB 이하의 PDF 파일을 업로드해 주세요.'],
      ['NO_TEXT', 'PDF에서 글자를 추출할 수 없습니다. 보험사 홈페이지에서 원본 약관을 다시 받아 주세요.'],
      ['NOT_TERMS', '보험약관이 아닌 문서입니다. 보험약관 PDF를 업로드해 주세요.'],
    ]) {
      await env.user.selectOptions(select, code)
      expect(within(modal).getByRole('alert').textContent).toBe(copy)
    }
    await env.user.click(within(modal).getByRole('button', { name: '다시 시도하기' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expectNoNetwork(env)
  })
})
