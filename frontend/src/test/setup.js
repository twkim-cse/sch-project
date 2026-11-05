import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

// jsdom does not implement native modal top-layer/inert behavior. These shims
// expose dialog lifecycle for DOM tests; actual focus trapping is browser-native.
Object.defineProperties(HTMLDialogElement.prototype, {
  showModal: {
    configurable: true,
    value() { this.setAttribute('open', '') },
  },
  close: {
    configurable: true,
    value() { this.removeAttribute('open') },
  },
})

Object.defineProperty(document.documentElement, 'clientWidth', {
  configurable: true,
  get: () => window.innerWidth,
})

beforeEach(() => {
  window.sessionStorage.clear()
  window.history.replaceState(null, '', '/home')
  vi.stubGlobal('scrollTo', vi.fn())
  Object.defineProperty(window, 'scrollY', {
    configurable: true,
    writable: true,
    value: 0,
  })
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.body.removeAttribute('style')
})
