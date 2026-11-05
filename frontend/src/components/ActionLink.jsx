import { Link } from 'react-router-dom'
import './ActionButton.css'
import { usePreviewTo } from '../features/preview/previewRouting.js'

function ActionLink({ to, children, variant = 'primary', className = '' }) {
  const previewTo = usePreviewTo()
  return (
    <Link to={previewTo(to)} className={`action-button action-button--${variant} ${className}`}>
      {children}
    </Link>
  )
}

export default ActionLink
