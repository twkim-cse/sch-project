import { useCallback, useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import { useAppNavigate } from '../preview/previewRouting.js'
import { useAnalysisSession } from './useAnalysisSession.js'
import { paths } from '../../routes/paths.js'
import NoAnalysisModal from '../../components/NoAnalysisModal.jsx'
import { AnalysisNavigationContext } from './AnalysisNavigationContext.js'

function AnalysisNavigationSession({ children }) {
  const { service, acceptCompleted } = useAnalysisSession()
  const navigate = useAppNavigate()
  const location = useLocation()
  const [dialog, setDialog] = useState(null)
  const [isChecking, setIsChecking] = useState(false)
  const pending = useRef(false)
  const currentLocation = useRef(location.key)
  const mounted = useRef(false)

  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])

  useEffect(() => {
    currentLocation.current = location.key
  }, [location.key])

  const closeDialog = useCallback(() => setDialog(null), [])

  const openResults = useCallback(async () => {
    if (pending.current) return
    pending.current = true
    setIsChecking(true)
    const requestedLocation = currentLocation.current

    try {
      const completed = await service.getCompletedAnalysis()
      if (!mounted.current || currentLocation.current !== requestedLocation) return
      if (completed) {
        acceptCompleted(completed)
        navigate(paths.results, {
          state: { analysisId: completed.analysis.analysisId },
        })
      } else {
        setDialog({ kind: 'empty', locationKey: requestedLocation })
      }
    } catch {
      if (mounted.current && currentLocation.current === requestedLocation) {
        setDialog({ kind: 'unavailable', locationKey: requestedLocation })
      }
    } finally {
      pending.current = false
      if (mounted.current) setIsChecking(false)
    }
  }, [navigate, service, acceptCompleted])

  return (
    <AnalysisNavigationContext.Provider value={{ openResults, isChecking }}>
      {children}
      {dialog && dialog.locationKey === location.key && (
        <NoAnalysisModal
          key={location.key}
          unavailable={dialog.kind === 'unavailable'}
          onClose={closeDialog}
        />
      )}
    </AnalysisNavigationContext.Provider>
  )
}

function AnalysisNavigationProvider(props) {
  const location = useLocation()
  // History can revisit the same location key. A new mounted session discards
  // old modal state and pending callbacks after EVERY navigation away.
  return <AnalysisNavigationSession key={location.key} {...props} />
}

export default AnalysisNavigationProvider
