import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App'
import { I18nProvider } from './i18n'
import { AuthProvider } from './state/AuthContext'
import { CatalogProvider } from './state/CatalogContext'
import './index.css'

const container = document.getElementById('root')
if (!container) {
  throw new Error('Root element missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <BrowserRouter>
      {/* Outermost of the three: the header renders language and currency together,
          and a language change must never remount the catalogue. */}
      <I18nProvider>
        <AuthProvider>
          <CatalogProvider>
            <App />
          </CatalogProvider>
        </AuthProvider>
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
)
