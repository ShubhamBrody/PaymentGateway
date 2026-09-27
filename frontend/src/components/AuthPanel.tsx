import { useState, type FormEvent } from 'react'
import { ApiError, createApi } from '../lib/api'

interface AuthPanelProps {
  onSignedIn: (token: string) => void
}

export function AuthPanel({ onSignedIn }: AuthPanelProps) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [businessName, setBusinessName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    const api = createApi(null)
    try {
      if (mode === 'register') {
        await api.register({ businessName, email, password })
      }
      const auth = await api.login({ email, password })
      onSignedIn(auth.data.accessToken)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not reach the payment server')
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth">
      <h1>Payment System Simulator</h1>
      <p className="muted">Sign in as a merchant to create payments and watch them move through the queues.</p>
      <form className="card" onSubmit={submit}>
        <h2>{mode === 'login' ? 'Sign in' : 'Create a merchant account'}</h2>
        {mode === 'register' && (
          <label>
            Business name
            <input value={businessName} onChange={(e) => setBusinessName(e.target.value)} required maxLength={100} />
          </label>
        )}
        <label>
          Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            minLength={mode === 'register' ? 15 : 1}
            maxLength={64}
          />
        </label>
        {mode === 'register' && <p className="hint">Use 15 to 64 characters.</p>}
        {error && (
          <p className="error" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={busy}>
          {mode === 'login' ? 'Sign in' : 'Create account'}
        </button>
        <button
          type="button"
          className="link"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError(null)
          }}
        >
          {mode === 'login' ? 'Create an account' : 'I already have an account'}
        </button>
      </form>
    </main>
  )
}
