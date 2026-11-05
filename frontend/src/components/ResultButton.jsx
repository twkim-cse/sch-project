import { useAnalysisNavigation } from '../features/analysis/useAnalysisNavigation.js'
import './ActionButton.css'

function ResultButton({ children, className = '' }) {
  const { openResults, isChecking } = useAnalysisNavigation()

  return (
    <button
      type="button"
      className={className}
      onClick={openResults}
      disabled={isChecking}
      aria-busy={isChecking}
    >
      {children}
    </button>
  )
}

export default ResultButton
