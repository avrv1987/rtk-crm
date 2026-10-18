import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ErrorBoundary } from '../shared/ui/ErrorBoundary'
import { MaxApp } from './MaxApp'
import { readInitData, type Bridge } from './startup'
import '../shared/theme/tokens.css'
import './maxApp.css'

// initData читается первым, до React и до любой работы с адресом: MAX передаёт её во фрагменте «#».
const bridge = (window as unknown as { WebApp?: Bridge }).WebApp
const initData = readInitData(bridge, window.location.hash)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ErrorBoundary>
      <MaxApp initData={initData} bridge={bridge} />
    </ErrorBoundary>
  </StrictMode>
)
