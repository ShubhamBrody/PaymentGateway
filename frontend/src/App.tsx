import { useEffect, useMemo, useState } from 'react'
import { AuthPanel } from './components/AuthPanel'
import { PaymentsPanel } from './components/PaymentsPanel'
import { SystemPanel } from './components/SystemPanel'
import { createApi } from './lib/api'
import type { Merchant } from './types'

const TOKEN_KEY = 'payment-system-token'

export default function App() {
  const [token, setToken] = useState<string | null>(() => sessionStorage.getItem(TOKEN_KEY))
  const [merchant, setMerchant] = useState<Merchant | null>(null)
  const [tab, setTab] = useState<'payments' | 'system'>('payments')

  const api = useMemo(
    () =>
      createApi(token, {
        onUnauthorized: () => {
          sessionStorage.removeItem(TOKEN_KEY)
          setToken(null)
        },
      }),
    [token],
  )

  useEffect(() => {
    if (token) {
      api
        .me()
        .then((response) => setMerchant(response.data))
        .catch(() => setMerchant(null))
    }
  }, [api, token])

  function signIn(newToken: string) {
    sessionStorage.setItem(TOKEN_KEY, newToken)
    setToken(newToken)
  }

  function signOut() {
    sessionStorage.removeItem(TOKEN_KEY)
    setToken(null)
    setMerchant(null)
  }

  if (!token) {
    return <AuthPanel onSignedIn={signIn} />
  }

  return (
    <div className="app">
      <header className="topbar">
        <strong>Payment System Simulator</strong>
        <nav>
          <button className={tab === 'payments' ? 'tab active' : 'tab'} onClick={() => setTab('payments')}>
            Payments
          </button>
          <button className={tab === 'system' ? 'tab active' : 'tab'} onClick={() => setTab('system')}>
            System
          </button>
        </nav>
        <span className="muted">{merchant?.businessName}</span>
        <button className="secondary" onClick={signOut}>
          Sign out
        </button>
      </header>
      <main>{tab === 'payments' ? <PaymentsPanel api={api} /> : <SystemPanel api={api} />}</main>
    </div>
  )
}
