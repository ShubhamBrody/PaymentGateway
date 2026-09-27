import { useEffect, useState } from 'react'
import { ApiError, type Api } from '../lib/api'
import type { SystemStats } from '../types'

const QUEUE_LABELS: Record<string, string> = {
  'payments:requests': 'Payment requests waiting for a worker',
  'bank:requests': 'Requests waiting at the bank',
  'bank:responses': 'Bank replies waiting to be applied',
  'payments:dead-letters': 'Dead letters (need investigation)',
}

export function SystemPanel({ api, refreshMs = 1000 }: { api: Api; refreshMs?: number }) {
  const [stats, setStats] = useState<SystemStats | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    const refresh = () =>
      api
        .stats()
        .then((response) => {
          if (active) {
            setStats(response.data)
            setError(null)
          }
        })
        .catch((err) => active && setError(err instanceof ApiError ? err.message : 'Could not load statistics'))
    void refresh()
    const timer = setInterval(refresh, refreshMs)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [api, refreshMs])

  if (!stats) {
    return <section className="card">{error ?? 'Loading statistics...'}</section>
  }

  return (
    <div className="system">
      <section className="card">
        <h2>Queues</h2>
        <p className="muted">
          Waiting is the number of unhandled messages. In progress means a worker has taken the message but has not
          acknowledged it yet; if that worker dies, another worker takes it over.
        </p>
        <table>
          <thead>
            <tr>
              <th>Queue</th>
              <th>Waiting</th>
              <th>In progress</th>
            </tr>
          </thead>
          <tbody>
            {stats.queues.map((queue) => (
              <tr key={queue.stream}>
                <td>
                  {QUEUE_LABELS[queue.stream] ?? queue.stream}
                  <br />
                  <code>{queue.stream}</code>
                </td>
                <td>{queue.length}</td>
                <td>{queue.pending}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section className="card">
        <h2>Payments</h2>
        <Counts values={stats.payments} />
        <h2>Payment requests</h2>
        <Counts values={stats.attempts} />
        <p>
          Receipts issued: <strong data-testid="receipts-count">{stats.receipts}</strong>
        </p>
        <p className="muted">Updated {new Date(stats.generatedAt).toLocaleTimeString()}</p>
        {error && <p className="error">{error}</p>}
      </section>
    </div>
  )
}

function Counts({ values }: { values: Record<string, number> }) {
  return (
    <dl className="counts">
      {Object.entries(values).map(([status, count]) => (
        <div key={status}>
          <dt>{status.replaceAll('_', ' ')}</dt>
          <dd>{count}</dd>
        </div>
      ))}
    </dl>
  )
}
