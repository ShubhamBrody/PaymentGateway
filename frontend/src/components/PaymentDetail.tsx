import { useEffect, useRef, useState } from 'react'
import { ApiError, type Api } from '../lib/api'
import { formatMinor } from '../lib/money'
import { newRequestKey, pollUntilFinal } from '../lib/paymentRequest'
import {
  BANK_SCENARIOS,
  type BankScenario,
  type Payment,
  type PaymentAttempt,
  type PaymentRequestStatus,
  type Receipt,
} from '../types'
import { StatusBadge } from './StatusBadge'

interface PaymentDetailProps {
  api: Api
  paymentId: string
  onChanged?: () => void
  pollIntervalMs?: number
}

interface ActiveRequest {
  key: string
  scenario: BankScenario
}

const PIPELINE = [
  { status: 'QUEUED', label: 'Queued for a worker' },
  { status: 'PENDING', label: 'Sent to the bank' },
  { status: 'FINAL', label: 'Bank replied' },
] as const

// Mount with key={paymentId} so switching payments starts with fresh request state.
export function PaymentDetail({ api, paymentId, onChanged, pollIntervalMs = 400 }: PaymentDetailProps) {
  const [payment, setPayment] = useState<Payment | null>(null)
  const [attempts, setAttempts] = useState<PaymentAttempt[]>([])
  const [receipt, setReceipt] = useState<Receipt | null>(null)
  const [scenario, setScenario] = useState<BankScenario>('APPROVE')
  const [request, setRequest] = useState<ActiveRequest | null>(null)
  const [status, setStatus] = useState<PaymentRequestStatus | null>(null)
  const [replayed, setReplayed] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [refreshCount, setRefreshCount] = useState(0)
  const polling = useRef<AbortController | null>(null)

  useEffect(() => {
    let active = true
    Promise.all([api.getPayment(paymentId), api.listAttempts(paymentId)])
      .then(async ([paymentResponse, attemptsResponse]) => {
        const paid = paymentResponse.data.status === 'SUCCEEDED'
        const receiptResponse = paid ? await api.getReceipt(paymentId) : null
        if (active) {
          setPayment(paymentResponse.data)
          setAttempts(attemptsResponse.data)
          setReceipt(receiptResponse?.data ?? null)
        }
      })
      .catch((err) => active && setError(message(err)))
    return () => {
      active = false
    }
  }, [api, paymentId, refreshCount])

  useEffect(() => () => polling.current?.abort(), [])

  const refresh = () => setRefreshCount((count) => count + 1)

  async function send(active: ActiveRequest) {
    setBusy(true)
    setError(null)
    polling.current?.abort()
    const controller = new AbortController()
    polling.current = controller
    try {
      const response = await api.submitPaymentRequest(paymentId, active.key, active.scenario)
      setReplayed(response.headers.get('Idempotent-Replayed') === 'true')
      setStatus(response.data)
      refresh()
      await pollUntilFinal(() => api.getPaymentRequestStatus(paymentId, active.key).then((r) => r.data), {
        intervalMs: pollIntervalMs,
        signal: controller.signal,
        onUpdate: setStatus,
      })
      refresh()
      onChanged?.()
    } catch (err) {
      if (!(err instanceof DOMException && err.name === 'AbortError')) {
        setError(message(err))
      }
    } finally {
      setBusy(false)
    }
  }

  function pay() {
    const active = { key: newRequestKey(), scenario }
    setRequest(active)
    void send(active)
  }

  async function cancel() {
    setError(null)
    try {
      await api.cancelPayment(paymentId)
      refresh()
      onChanged?.()
    } catch (err) {
      setError(message(err))
    }
  }

  if (!payment) {
    return <section className="card">{error ? <p className="error">{error}</p> : <p>Loading payment...</p>}</section>
  }

  const stage = status ? (['QUEUED', 'PENDING'].includes(status.status) ? status.status : 'FINAL') : null
  return (
    <section className="card detail">
      <header className="detail-header">
        <div>
          <h2>{formatMinor(payment.amount, payment.currency)}</h2>
          <p className="muted">
            {payment.description || 'No description'}
            {payment.orderReference ? ` - order ${payment.orderReference}` : ''}
          </p>
        </div>
        <StatusBadge status={payment.status} />
      </header>

      <div className="pay-controls">
        <label>
          Bank behaviour
          <select value={scenario} onChange={(e) => setScenario(e.target.value as BankScenario)} disabled={busy}>
            {BANK_SCENARIOS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <button onClick={pay} disabled={busy || payment.status !== 'AWAITING_PAYMENT'}>
          Pay
        </button>
        <button className="secondary" onClick={() => request && void send(request)} disabled={!request}>
          Retry same request
        </button>
        <button className="danger" onClick={cancel} disabled={busy || payment.status !== 'AWAITING_PAYMENT'}>
          Cancel payment
        </button>
      </div>

      {request && (
        <div className="request">
          <dl>
            <dt>Idempotency key</dt>
            <dd>
              <code data-testid="request-key">{request.key}</code>
            </dd>
            <dt>Status</dt>
            <dd data-testid="request-status">{status?.status ?? 'SENDING'}</dd>
            <dt>Last reply replayed</dt>
            <dd data-testid="replayed">{replayed === null ? '-' : replayed ? 'Yes' : 'No'}</dd>
            <dt>Read from</dt>
            <dd>{status?.source ?? '-'}</dd>
            <dt>Bank requests sent</dt>
            <dd>{status?.bankRequestCount ?? 0}</dd>
          </dl>
          <ol className="pipeline">
            {PIPELINE.map((step) => (
              <li key={step.status} className={stage === step.status ? 'active' : ''}>
                {step.label}
              </li>
            ))}
          </ol>
        </div>
      )}

      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}

      {receipt && (
        <div className="receipt">
          <h3>Receipt</h3>
          <p>
            <strong data-testid="receipt-number">{receipt.receiptNumber}</strong>
          </p>
          <p>
            {formatMinor(receipt.amount, receipt.currency)} paid, bank reference {receipt.bankReference}, issued{' '}
            {new Date(receipt.issuedAt).toLocaleString()}
          </p>
        </div>
      )}

      <h3>Attempts</h3>
      {attempts.length === 0 ? (
        <p className="muted">No payment requests yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th>Idempotency key</th>
              <th>Bank behaviour</th>
              <th>Status</th>
              <th>Bank requests</th>
              <th>Reason</th>
              <th>Receipt</th>
            </tr>
          </thead>
          <tbody>
            {attempts.map((attempt) => (
              <tr key={attempt.id}>
                <td>
                  <code>{attempt.idempotencyKey}</code>
                </td>
                <td>{attempt.scenario}</td>
                <td>
                  <StatusBadge status={attempt.status} />
                </td>
                <td>{attempt.bankRequestCount}</td>
                <td>{attempt.declineReason ?? '-'}</td>
                <td>{attempt.receiptNumber ?? '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

function message(err: unknown): string {
  return err instanceof ApiError ? err.message : err instanceof Error ? err.message : 'Something went wrong'
}
