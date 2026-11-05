import { useContext } from 'react'
import { AnalysisNavigationContext } from './AnalysisNavigationContext.js'

export function useAnalysisNavigation() {
  const value = useContext(AnalysisNavigationContext)
  if (!value) throw new Error('AnalysisNavigationProvider is required.')
  return value
}
