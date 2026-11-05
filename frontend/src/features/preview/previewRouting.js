import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePreviewMode } from './usePreviewMode.js'

export function withPreview(to, active) {
  if (!active || typeof to === 'number') return to
  if (typeof to === 'string') {
    if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(to)) return to
    const [pathAndSearch, ...hash] = to.split('#')
    const queryIndex = pathAndSearch.indexOf('?')
    const pathname = queryIndex < 0 ? pathAndSearch : pathAndSearch.slice(0, queryIndex)
    const search = new URLSearchParams(queryIndex < 0 ? '' : pathAndSearch.slice(queryIndex + 1))
    search.set('preview', '1')
    return `${pathname}?${search}${hash.length ? `#${hash.join('#')}` : ''}`
  }
  const search = new URLSearchParams(to.search || '')
  search.set('preview', '1')
  return { ...to, search: `?${search}` }
}

export function usePreviewTo() {
  const { active } = usePreviewMode()
  return useCallback((to) => withPreview(to, active), [active])
}

export function useAppNavigate() {
  const navigate = useNavigate()
  const to = usePreviewTo()
  return useCallback((destination, options) => navigate(to(destination), options), [navigate, to])
}
