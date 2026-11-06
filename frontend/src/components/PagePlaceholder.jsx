import Header from './Header.jsx'
import './PagePlaceholder.css'

function PagePlaceholder({ title, children }) {
  return (
    <div className="page-shell">
      <Header />
      <main className="page-placeholder">
        <h1>{title}</h1>
        {children}
      </main>
    </div>
  )
}

export default PagePlaceholder
