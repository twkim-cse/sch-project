import { useContext } from 'react'
import { PreviewModeContext } from './PreviewModeContext.js'

export function usePreviewMode() {
  return useContext(PreviewModeContext)
}
