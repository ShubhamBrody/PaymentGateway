#!/usr/bin/env node
// Load generator for the payment system. Registers merchants, runs many concurrent customer payment journeys
// through the queue, then checks every payment for consistency. Needs only Node 22+ (built-in fetch).
import { randomInt, randomUUID } from 'node:crypto'
import { parseArgs } from 'node:util'

const { values: options } = parseArgs({
  options: {
    'base-url': { type: 'string', default: 'http://127.0.0.1:8080' },
    merchants: { type: 'string', default: '1000' },
    customers: { type: 'string', default: '5000' },
    concurrency: { type: 'string', default: '200' },
    'signup-concurrency': { type: 'string', default: '32' },
    'duplicate-rate': { type: 'string', default: '0.3' },
    'new-key-retry-rate': { type: 'string', default: '0.5' },
    scenario: { type: 'string', default: 'RANDOM' },
    'poll-interval': { type: 'string', default: '250' },
    'journey-timeout': { type: 'string', default: '90000' },
  },
})

const BASE = `${options['base-url']}/api/v1`
const MERCHANTS = Number(options.merchants)
const CUSTOMERS = Number(options.customers)
const CONCURRENCY = Number(options.concurrency)
const SIGNUP_CONCURRENCY = Number(options['signup-concurrency'])
const DUPLICATE_RATE = Number(options['duplicate-rate'])
const NEW_KEY_RETRY_RATE = Number(options['new-key-retry-rate'])
const SCENARIO = options.scenario
const POLL_INTERVAL = Number(options['poll-interval'])
const JOURNEY_TIMEOUT = Number(options['journey-timeout'])
const PASSWORD = 'load-test-password-0001'
const FINAL = new Set(['SUCCEEDED', 'DECLINED', 'FAILED'])

const metrics = new Map()
const outcomes = new Map()
const violations = []
const errors = []
let requestCount = 0

class HttpError extends Error {
  constructor(status, body) {
    super(`HTTP ${status}: ${body?.detail ?? JSON.stringify(body)}`)
    this.status = status
  }
}

function record(label, millis, status) {
  let entry = metrics.get(label)
  if (!entry) {
    entry = { durations: [], statuses: new Map() }
    metrics.set(label, entry)
  }
  entry.durations.push(millis)
  entry.statuses.set(status, (entry.statuses.get(status) ?? 0) + 1)
}

function count(map, key) {
  map.set(key, (map.get(key) ?? 0) + 1)
}

async function call(label, method, path, { token, body, headers = {}, expect = [200] } = {}) {
  const started = performance.now()
  requestCount++
  let response
  try {
    response = await fetch(BASE + path, {
      method,
      headers: {
        Accept: 'application/json',
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    record(label, performance.now() - started, 'network-error')
    throw error
  }
  const text = await response.text()
  record(label, performance.now() - started, response.status)
  const data = text ? JSON.parse(text) : null
  if (!expect.includes(response.status)) {
    throw new HttpError(response.status, data)
  }
  return { status: response.status, data, headers: response.headers }
}

async function pool(items, concurrency, worker, label) {
  let next = 0
  let done = 0
  const started = Date.now()
  const progress = setInterval(() => {
    process.stdout.write(`\r  ${label}: ${done}/${items.length} (${((Date.now() - started) / 1000).toFixed(0)}s)`)
  }, 1000)
  async function lane() {
    while (next < items.length) {
      const index = next++
      try {
        await worker(items[index], index)
      } catch (error) {
        errors.push(`${label} #${index}: ${error.message}`)
      }
      done++
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, lane))
  clearInterval(progress)
  process.stdout.write(`\r  ${label}: ${done}/${items.length} in ${((Date.now() - started) / 1000).toFixed(1)}s\n`)
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// The retry mechanism: a network failure, 429 or 5xx is retried with the SAME idempotency key.
async function submitPaymentRequest(token, paymentId, key, scenario) {
  for (let attempt = 0; ; attempt++) {
    try {
      const response = await call('submit payment request', 'POST', `/payments/${paymentId}/attempts`, {
        token,
        body: { scenario },
        headers: { 'Idempotency-Key': key },
        expect: [202],
      })
      return { status: response.data, replayed: response.headers.get('Idempotent-Replayed') === 'true' }
    } catch (error) {
      const retryable = !(error instanceof HttpError) || error.status === 429 || error.status >= 500
      if (!retryable || attempt >= 4) {
        throw error
      }
      await sleep(100 * 2 ** attempt + randomInt(100))
    }
  }
}

async function waitForFinal(token, paymentId, key) {
  const deadline = Date.now() + JOURNEY_TIMEOUT
  for (;;) {
    const response = await call('poll request status', 'GET', `/payments/${paymentId}/requests/${key}`, { token })
    if (FINAL.has(response.data.status)) {
      return response.data
    }
    if (Date.now() > deadline) {
      throw new Error(`payment request ${key} was not final after ${JOURNEY_TIMEOUT} ms`)
    }
    await sleep(POLL_INTERVAL)
  }
}

async function signUp(_, index) {
  const email = `load-${runId}-${index}@example.com`
  await call('register merchant', 'POST', '/auth/register', {
    body: { businessName: `Load Merchant ${index}`, email, password: PASSWORD },
    expect: [201],
  })
  const login = await call('login', 'POST', '/auth/login', { body: { email, password: PASSWORD } })
  merchants.push(login.data.accessToken)
}

async function customerJourney(_, index) {
  const token = merchants[randomInt(merchants.length)]
  const amount = 100 + randomInt(500_000)
  const created = await call('create payment', 'POST', '/payments', {
    token,
    body: { amount, currency: 'INR', description: `Load test order ${index}` },
    headers: { 'Idempotency-Key': `payment-${randomUUID()}` },
    expect: [201],
  })
  const payment = { token, id: created.data.id, amount, keys: [] }
  payments.push(payment)

  for (let round = 1; round <= 2; round++) {
    // Generated once per payment request; every retry below reuses it.
    const key = `load-${randomUUID()}`
    payment.keys.push(key)
    const submittedAt = performance.now()
    const first = await submitPaymentRequest(token, payment.id, key, SCENARIO)
    if (Math.random() < DUPLICATE_RATE) {
      const duplicate = await submitPaymentRequest(token, payment.id, key, SCENARIO)
      if (!duplicate.replayed || duplicate.status.attemptId !== first.status.attemptId) {
        violations.push(`retry of ${key} was not replayed as the original attempt`)
      }
    }
    const final = await waitForFinal(token, payment.id, key)
    record('request to final status', performance.now() - submittedAt, final.status)
    count(outcomes, final.status)
    if (final.status === 'SUCCEEDED') {
      if (!final.receiptNumber) {
        violations.push(`payment ${payment.id} succeeded without a receipt number`)
      }
      return
    }
    if (Math.random() >= NEW_KEY_RETRY_RATE) {
      return
    }
  }
}

async function verify(payment) {
  const { token, id } = payment
  const [current, attempts] = await Promise.all([
    call('verify payment', 'GET', `/payments/${id}`, { token }),
    call('verify attempts', 'GET', `/payments/${id}/attempts`, { token }),
  ])
  const succeeded = attempts.data.filter((attempt) => attempt.status === 'SUCCEEDED')
  const unfinished = attempts.data.filter((attempt) => !FINAL.has(attempt.status))
  if (attempts.data.length !== payment.keys.length) {
    violations.push(`payment ${id} has ${attempts.data.length} attempts for ${payment.keys.length} keys`)
  }
  if (unfinished.length > 0) {
    violations.push(`payment ${id} still has unfinished attempts`)
  }
  if (current.data.status === 'SUCCEEDED') {
    const receipt = await call('verify receipt', 'GET', `/payments/${id}/receipt`, { token })
    if (succeeded.length !== 1 || receipt.data.attemptId !== succeeded[0].id || receipt.data.amount !== payment.amount) {
      violations.push(`payment ${id} receipt does not match its single successful attempt`)
    }
  } else {
    await call('verify receipt', 'GET', `/payments/${id}/receipt`, { token, expect: [404] })
    if (succeeded.length !== 0) {
      violations.push(`payment ${id} is ${current.data.status} but has a successful attempt`)
    }
  }
}

function percentile(sorted, p) {
  return sorted.length === 0 ? 0 : sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))]
}

function report(runSeconds, stats) {
  console.log('\nLatency by operation (ms)')
  console.table(
    [...metrics.entries()].map(([operation, entry]) => {
      const sorted = [...entry.durations].sort((a, b) => a - b)
      return {
        operation,
        count: sorted.length,
        p50: Math.round(percentile(sorted, 50)),
        p95: Math.round(percentile(sorted, 95)),
        p99: Math.round(percentile(sorted, 99)),
        max: Math.round(sorted.at(-1) ?? 0),
        statuses: [...entry.statuses.entries()].map(([status, n]) => `${status}:${n}`).join(' '),
      }
    }),
  )
  console.log('Payment request outcomes:', Object.fromEntries(outcomes))
  console.log(`Requests: ${requestCount} in ${runSeconds.toFixed(1)}s (${(requestCount / runSeconds).toFixed(0)} req/s)`)
  console.log('Queues after the run:', stats.queues.map((q) => `${q.stream} length=${q.length} pending=${q.pending}`))
  console.log('Payments:', stats.payments, 'Receipts:', stats.receipts)
  console.log(`Errors: ${errors.length}  Consistency violations: ${violations.length}`)
  for (const line of [...errors, ...violations].slice(0, 20)) {
    console.log(`  - ${line}`)
  }
}

const runId = randomUUID().slice(0, 8)
const merchants = []
const payments = []

console.log(`Load test ${runId}: ${MERCHANTS} merchants, ${CUSTOMERS} customers, concurrency ${CONCURRENCY}`)
const started = performance.now()
await pool(Array.from({ length: MERCHANTS }), SIGNUP_CONCURRENCY, signUp, 'merchant sign-up')
if (merchants.length === 0) {
  console.error('No merchant could sign up; is the server running?', errors.slice(0, 3))
  process.exit(1)
}
await pool(Array.from({ length: CUSTOMERS }), CONCURRENCY, customerJourney, 'customer payments')
await pool(payments, Math.min(CONCURRENCY, 100), verify, 'consistency check')
const stats = (await call('stats', 'GET', '/simulator/stats', { token: merchants[0] })).data
report((performance.now() - started) / 1000, stats)
process.exit(errors.length > 0 || violations.length > 0 ? 1 : 0)
