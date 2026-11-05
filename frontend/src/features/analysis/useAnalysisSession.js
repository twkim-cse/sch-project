import { useContext } from 'react'
import { AnalysisSessionContext } from './AnalysisSessionContext.js'

export function useAnalysisSession() {
  const value = useContext(AnalysisSessionContext)
  if (!value) throw new Error('AnalysisSessionProvider is required.')
  return value
}
