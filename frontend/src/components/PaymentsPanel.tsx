import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, type Api } from '../lib/api'
import { formatMinor, toMinorUnits } from '../lib/money'
import { newRequestKey } from '../lib/paymentRequest'
import { PAYMENT_STATUSES, type Page, type Payment, type PaymentStatus } from '../types'
import { PaymentDetail } from './PaymentDetail'
import { StatusBadge } from './StatusBadge'

const PAGE_SIZE = 10

export function PaymentsPanel({ api }: { api: Api }) {
  const [amount, setAmount] = useState('')
  const [description, setDescription] = useState('')
  const [orderReference, setOrderReference] = useState('')
  const [filter, setFilter] = useState<PaymentStatus | ''>('')
  const [page, setPage] = useState(0)
  const [payments, setPayments] = useState<Page<Payment> | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshCount, setRefreshCount] = useState(0)

  useEffect(() => {
    let active = true
    api
      .listPayments({ page, size: PAGE_SIZE, status: filter || undefined })
      .then((response) => active && setPayments(response.data))
      .catch((err) => active && setError(err instanceof ApiError ? err.message : 'Could not load payments'))
    return () => {
      active = false
    }
  }, [api, page, filter, refreshCount])

  const refresh = () => setRefreshCount((count) => count + 1)

  async function create(event: FormEvent) {
    event.preventDefault()
    setError(null)
    const minor = toMinorUnits(amount)
    if (minor === null) {
      setError('Enter an amount between 0.01 and 10,00,000 with at most two decimals')
      return
    }
    try {
      const response = await api.createPayment(
        {
          amount: minor,
          currency: 'INR',
          description: description || undefined,
          orderReference: orderReference || undefined,
        },
        newRequestKey('payment'),
      )
      setAmount('')
      setDescription('')
      setOrderReference('')
      setSelected(response.data.id)
      refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the payment')
    }
  }

  return (
    <div className="payments">
      <div className="column">
        <form className="card" onSubmit={create}>
          <h2>New payment</h2>
          <label>
            Amount (INR)
            <input value={amount} onChange={(e) => setAmount(e.target.value)} inputMode="decimal" placeholder="249.99" required />
          </label>
          <label>
            Description
            <input value={description} onChange={(e) => setDescription(e.target.value)} maxLength={255} />
          </label>
          <label>
            Order reference
            <input value={orderReference} onChange={(e) => setOrderReference(e.target.value)} maxLength={64} />
          </label>
          <button type="submit">Create payment</button>
          {error && (
            <p className="error" role="alert">
              {error}
            </p>
          )}
        </form>

        <section className="card">
          <header className="list-header">
            <h2>Payments</h2>
            <select
              aria-label="Filter by status"
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value as PaymentStatus | '')
                setPage(0)
              }}
            >
              <option value="">All statuses</option>
              {PAYMENT_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status.replaceAll('_', ' ')}
                </option>
              ))}
            </select>
            <button className="secondary" onClick={refresh}>
              Refresh
            </button>
          </header>
          {payments && payments.content.length === 0 && <p className="muted">No payments yet.</p>}
          <ul className="payment-list">
            {payments?.content.map((payment) => (
              <li key={payment.id}>
                <button
                  className={`payment-row ${selected === payment.id ? 'selected' : ''}`}
                  onClick={() => setSelected(payment.id)}
                >
                  <span>{formatMinor(payment.amount, payment.currency)}</span>
                  <span className="muted">{payment.description ?? payment.id.slice(0, 8)}</span>
                  <StatusBadge status={payment.status} />
                </button>
              </li>
            ))}
          </ul>
          {payments && payments.totalPages > 1 && (
            <footer className="pager">
              <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                Previous
              </button>
              <span>
                Page {payments.page + 1} of {payments.totalPages}
              </span>
              <button className="secondary" disabled={page + 1 >= payments.totalPages} onClick={() => setPage(page + 1)}>
                Next
              </button>
            </footer>
          )}
        </section>
      </div>

      <div className="column wide">
        {selected ? (
          <PaymentDetail key={selected} api={api} paymentId={selected} onChanged={refresh} />
        ) : (
          <section className="card muted">Create or select a payment to simulate paying it.</section>
        )}
      </div>
    </div>
  )
}
