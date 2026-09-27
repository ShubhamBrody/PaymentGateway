#!/usr/bin/env node
// Load generator for the payment system. Registers merchants, then runs many concurrent customer payment journeys
// through the queue, each paying from the customer's bank. Afterwards it checks that every total the customers saw,
// overall and per bank, matches the backend, and checks a random sample of payments in full. Needs Node 22+.
// Journeys run on worker threads because a single JavaScript thread tops out near 3,000 requests per second.
import { randomInt, randomUUID } from 'node:crypto'
import { parseArgs } from 'node:util'
import { isMainThread, parentPort, Worker, workerData } from 'node:worker_threads'

const { values: options } = parseArgs({
  options: {
    'base-url': { type: 'string', default: 'http://127.0.0.1:8080' },
    merchants: { type: 'string', default: '1000' },
    customers: { type: 'string', default: '5000' },
    banks: { type: 'string', default: '30' },
    concurrency: { type: 'string', default: '200' },
    workers: { type: 'string', default: '4' },
    'signup-concurrency': { type: 'string', default: '32' },
    'duplicate-rate': { type: 'string', default: '0.3' },
    'new-key-retry-rate': { type: 'string', default: '0.5' },
    scenario: { type: 'string', default: 'RANDOM' },
    'poll-interval': { type: 'string', default: '250' },
    'journey-timeout': { type: 'string', default: '90000' },
    'verify-sample': { type: 'string', default: '20000' },
    'progress-interval': { type: 'string', default: '10' },
  },
})

const BASE = `${options['base-url']}/api/v1`
const MERCHANTS = Number(options.merchants)
const CUSTOMERS = Number(options.customers)
const BANKS = Number(options.banks)
const CONCURRENCY = Number(options.concurrency)
const WORKERS = Number(options.workers)
const SIGNUP_CONCURRENCY = Number(options['signup-concurrency'])
const DUPLICATE_RATE = Number(options['duplicate-rate'])
const NEW_KEY_RETRY_RATE = Number(options['new-key-retry-rate'])
const SCENARIO = options.scenario
const POLL_INTERVAL = Number(options['poll-interval'])
const JOURNEY_TIMEOUT = Number(options['journey-timeout'])
const VERIFY_SAMPLE = Number(options['verify-sample'])
const PROGRESS_INTERVAL = Number(options['progress-interval']) * 1000
const PASSWORD = 'load-test-password-0001'
const FINAL = new Set(['SUCCEEDED', 'DECLINED', 'FAILED'])
const LISTED_PROBLEMS = 20

// 1 ms buckets below 1 s, 10 ms below 10 s, then 100 ms, so memory stays the same at any volume.
class Histogram {
  counts = new Float64Array(3_000)
  total = 0
  max = 0

  add(ms) {
    const bucket = ms < 1_000 ? Math.floor(ms)
      : ms < 10_000 ? 1_000 + Math.floor((ms - 1_000) / 10)
        : Math.min(2_999, 1_900 + Math.floor((ms - 10_000) / 100))
    this.counts[bucket]++
    this.total++
    this.max = Math.max(this.max, ms)
  }

  percentile(p) {
    const wanted = Math.ceil((p / 100) * this.total)
    let seen = 0
    for (let bucket = 0; bucket < this.counts.length; bucket++) {
      seen += this.counts[bucket]
      if (seen > 0 && seen >= wanted) {
        return bucket < 1_000 ? bucket : bucket < 1_900 ? 1_000 + (bucket - 1_000) * 10 : 10_000 + (bucket - 1_900) * 100
      }
    }
    return 0
  }
}

const metrics = new Map()
const outcomes = new Map()
const byBank = new Map()
const violations = { count: 0, examples: [] }
const errors = { count: 0, examples: [] }
let requestCount = 0

function note(problems, message) {
  problems.count++
  if (problems.examples.length < LISTED_PROBLEMS) {
    problems.examples.push(message)
  }
}

class HttpError extends Error {
  constructor(status, body) {
    super(`HTTP ${status}: ${body?.detail ?? JSON.stringify(body)}`)
    this.status = status
  }
}

function record(label, millis, status) {
  let entry = metrics.get(label)
  if (!entry) {
    entry = { latency: new Histogram(), statuses: new Map() }
    metrics.set(label, entry)
  }
  entry.latency.add(millis)
  count(entry.statuses, status)
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

async function pool(label, total, concurrency, task, onDone = () => {}) {
  let next = 0
  async function lane() {
    while (next < total) {
      const index = next++
      try {
        await task(index)
      } catch (error) {
        note(errors, `${label} #${index}: ${error.message}`)
      }
      onDone()
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, total) }, lane))
}

// Prints a progress line every progress-interval; call the returned function to print the final line.
function progressLines(label, total, snapshot) {
  const started = Date.now()
  let last = { at: started, ...snapshot() }
  const timer = setInterval(() => {
    const now = Date.now()
    const current = snapshot()
    const rate = ((current.done - last.done) * 1000) / (now - last.at)
    const requestRate = ((current.requests - last.requests) * 1000) / (now - last.at)
    const left = rate > 0 ? `${Math.round((total - current.done) / rate)}s` : '?'
    console.log(`  ${label}: ${current.done}/${total} after ${seconds(started)}, ${rate.toFixed(0)}/s, `
      + `${requestRate.toFixed(0)} req/s, errors ${current.errors}, violations ${current.violations}, about ${left} left`)
    last = { at: now, ...current }
  }, PROGRESS_INTERVAL)
  return () => {
    clearInterval(timer)
    console.log(`  ${label}: ${snapshot().done}/${total} in ${seconds(started)}`)
  }
}

const seconds = (since) => `${((Date.now() - since) / 1000).toFixed(1)}s`
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

// The retry mechanism: a network failure, 429 or 5xx is retried with the SAME idempotency key.
async function withRetries(action) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await action()
    } catch (error) {
      const retryable = !(error instanceof HttpError) || error.status === 429 || error.status >= 500
      if (!retryable || attempt >= 4) {
        throw error
      }
      await sleep(100 * 2 ** attempt + randomInt(100))
    }
  }
}

async function submitPaymentRequest(token, paymentId, key, bank) {
  const response = await withRetries(() => call('submit payment request', 'POST', `/payments/${paymentId}/attempts`, {
    token,
    body: { scenario: SCENARIO, bank },
    headers: { 'Idempotency-Key': key },
    expect: [202],
  }))
  return { status: response.data, replayed: response.headers.get('Idempotent-Replayed') === 'true' }
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

async function logIn(merchant) {
  const login = await call('login', 'POST', '/auth/login', { body: { email: merchant.email, password: PASSWORD } })
  merchant.token = login.data.accessToken
  merchant.expiresAt = Date.now() + login.data.expiresInSeconds * 1000
}

// Tokens last minutes and a big run lasts longer, so a merchant signs in again shortly before its token expires.
async function tokenFor(merchant) {
  if (Date.now() > merchant.expiresAt - 120_000) {
    merchant.refreshing ??= logIn(merchant).finally(() => {
      merchant.refreshing = null
    })
    await merchant.refreshing
  }
  return merchant.token
}

async function signUp(index) {
  const merchant = { email: `load-${runId}-${index}@example.com`, token: null, expiresAt: 0, refreshing: null }
  await call('register merchant', 'POST', '/auth/register', {
    body: { businessName: `Load Merchant ${index}`, email: merchant.email, password: PASSWORD },
    expect: [201],
  })
  await logIn(merchant)
  merchants.push(merchant)
}

// Bigger banks have more customers: the n-th bank gets a share proportional to 1 / sqrt(n).
function bankPicker(banks) {
  const weights = banks.map((_, index) => 1 / Math.sqrt(index + 1))
  const total = weights.reduce((sum, weight) => sum + weight, 0)
  let running = 0
  const edges = weights.map((weight) => (running += weight / total))
  return () => {
    const roll = Math.random()
    const index = edges.findIndex((edge) => roll < edge)
    return banks[index === -1 ? banks.length - 1 : index].code
  }
}

function bankTally(code) {
  let tally = byBank.get(code)
  if (!tally) {
    tally = { requests: 0, SUCCEEDED: 0, DECLINED: 0, FAILED: 0, latency: new Histogram() }
    byBank.set(code, tally)
  }
  return tally
}

// Keeps a uniform random sample of payments for the full check, without holding every payment in memory.
function keepForSampleCheck(payment) {
  paymentsSeen++
  if (sampled.length < sampleSize) {
    sampled.push(payment)
  } else if (sampleSize > 0) {
    const slot = randomInt(paymentsSeen)
    if (slot < sampleSize) {
      sampled[slot] = payment
    }
  }
}

async function customerJourney(index) {
  const merchant = merchants[randomInt(merchants.length)]
  const token = await tokenFor(merchant)
  const bank = pickBank()
  const amount = 100 + randomInt(500_000)
  const paymentKey = `payment-${randomUUID()}`
  const created = await withRetries(() => call('create payment', 'POST', '/payments', {
    token,
    body: { amount, currency: 'INR', description: `Load test order ${firstCustomer + index}` },
    headers: { 'Idempotency-Key': paymentKey },
    expect: [201],
  }))
  paymentsCreated++
  const payment = { merchant, id: created.data.id, amount, bank, keys: [] }
  keepForSampleCheck(payment)

  for (let round = 1; round <= 2; round++) {
    // Generated once per payment request; every retry below reuses it.
    const key = `load-${randomUUID()}`
    payment.keys.push(key)
    const submittedAt = performance.now()
    const first = await submitPaymentRequest(token, payment.id, key, bank)
    if (Math.random() < DUPLICATE_RATE) {
      const duplicate = await submitPaymentRequest(token, payment.id, key, bank)
      if (!duplicate.replayed || duplicate.status.attemptId !== first.status.attemptId) {
        note(violations, `retry of ${key} was not replayed as the original attempt`)
      }
    }
    const final = await waitForFinal(token, payment.id, key)
    const took = performance.now() - submittedAt
    record('request to final status', took, final.status)
    count(outcomes, final.status)
    const tally = bankTally(bank)
    tally.requests++
    tally[final.status]++
    tally.latency.add(took)
    if (final.bank !== bank) {
      note(violations, `payment request ${key} was answered by ${final.bank} instead of ${bank}`)
    }
    if (final.status === 'SUCCEEDED') {
      if (!final.receiptNumber) {
        note(violations, `payment ${payment.id} succeeded without a receipt number`)
      }
      return
    }
    if (Math.random() >= NEW_KEY_RETRY_RATE) {
      return
    }
  }
}

async function verify(payment) {
  const token = await tokenFor(payment.merchant)
  const { id } = payment
  const [current, attempts] = await Promise.all([
    call('verify payment', 'GET', `/payments/${id}`, { token }),
    call('verify attempts', 'GET', `/payments/${id}/attempts`, { token }),
  ])
  const succeeded = attempts.data.filter((attempt) => attempt.status === 'SUCCEEDED')
  const unfinished = attempts.data.filter((attempt) => !FINAL.has(attempt.status))
  if (attempts.data.length !== payment.keys.length) {
    note(violations, `payment ${id} has ${attempts.data.length} attempts for ${payment.keys.length} keys`)
  }
  if (unfinished.length > 0) {
    note(violations, `payment ${id} still has unfinished attempts`)
  }
  if (attempts.data.some((attempt) => attempt.bank !== payment.bank)) {
    note(violations, `payment ${id} has an attempt sent to a bank other than ${payment.bank}`)
  }
  if (current.data.status === 'SUCCEEDED') {
    const receipt = await call('verify receipt', 'GET', `/payments/${id}/receipt`, { token })
    if (succeeded.length !== 1 || receipt.data.attemptId !== succeeded[0].id || receipt.data.amount !== payment.amount) {
      note(violations, `payment ${id} receipt does not match its single successful attempt`)
    }
  } else {
    await call('verify receipt', 'GET', `/payments/${id}/receipt`, { token, expect: [404] })
    if (succeeded.length !== 0) {
      note(violations, `payment ${id} is ${current.data.status} but has a successful attempt`)
    }
  }
}

async function stats() {
  return (await call('stats', 'GET', '/simulator/stats', { token: await tokenFor(merchants[0]) })).data
}

// A duplicate bank reply can still be in flight after the last customer is done, so wait for empty queues.
async function statsOnceDrained() {
  const deadline = Date.now() + 120_000
  for (;;) {
    const current = await stats()
    const busy = current.queues.some(
      (queue) => queue.stream !== 'payments:dead-letters' && (queue.length > 0 || queue.pending > 0))
    if (!busy || Date.now() > deadline) {
      return current
    }
    await sleep(2_000)
  }
}

// Compares what customers saw with what the backend stored during the run, overall and for every bank.
function checkTotals(before, after) {
  const added = (pick) => (pick(after) ?? 0) - (pick(before) ?? 0)
  const sum = (counts) => Object.values(counts).reduce((total, value) => total + value, 0)
  const newPayments = added((stats) => sum(stats.payments))
  if (newPayments !== paymentsCreated) {
    note(violations, `the backend stored ${newPayments} new payments; customers created ${paymentsCreated}`)
  }
  for (const status of FINAL) {
    const stored = added((stats) => stats.attempts[status])
    if (stored !== (outcomes.get(status) ?? 0)) {
      note(violations, `the backend stored ${stored} ${status} attempts; customers saw ${outcomes.get(status) ?? 0}`)
    }
  }
  for (const status of ['QUEUED', 'PENDING']) {
    const left = added((stats) => stats.attempts[status])
    if (left !== 0) {
      note(violations, `${left} attempts are still ${status}`)
    }
  }
  const receipts = added((stats) => stats.receipts)
  const succeededPayments = added((stats) => stats.payments.SUCCEEDED)
  if (receipts !== succeededPayments || receipts !== (outcomes.get('SUCCEEDED') ?? 0)) {
    note(violations, `${receipts} new receipts for ${succeededPayments} newly succeeded payments`)
  }
  for (const [code, tally] of byBank) {
    for (const status of FINAL) {
      const stored = added((stats) => stats.attemptsByBank[code]?.[status])
      if (stored !== tally[status]) {
        note(violations, `${code} stored ${stored} ${status} attempts; its customers saw ${tally[status]}`)
      }
    }
  }
  const deadLetters = added((stats) => stats.queues.find((queue) => queue.stream === 'payments:dead-letters')?.length)
  if (deadLetters !== 0) {
    note(violations, `${deadLetters} messages went to dead letters`)
  }
}

function latencyRow(histogram) {
  return {
    p50: Math.round(histogram.percentile(50)),
    p95: Math.round(histogram.percentile(95)),
    p99: Math.round(histogram.percentile(99)),
    max: Math.round(histogram.max),
  }
}

function report(runSeconds, journeySeconds, journeyRequests, after, banks) {
  console.log('\nLatency by operation (ms)')
  console.table(
    [...metrics.entries()].map(([operation, entry]) => ({
      operation,
      count: entry.latency.total,
      ...latencyRow(entry.latency),
      statuses: [...entry.statuses.entries()].map(([status, n]) => `${status}:${n}`).join(' '),
    })),
  )
  console.log('Payment requests by bank (request to final status in ms)')
  console.table(
    banks.filter((bank) => byBank.has(bank.code)).map((bank) => {
      const tally = byBank.get(bank.code)
      return {
        bank: bank.code,
        'reply ms': bank.approveDelayMillis,
        'decline rate': `${(bank.declineRate * 100).toFixed(1)}%`,
        requests: tally.requests,
        succeeded: tally.SUCCEEDED,
        declined: tally.DECLINED,
        failed: tally.FAILED,
        'declined share': `${((tally.DECLINED / tally.requests) * 100).toFixed(1)}%`,
        ...latencyRow(tally.latency),
      }
    }),
  )
  console.log('Payment request outcomes:', Object.fromEntries(outcomes))
  console.log(`Customer payments: ${CUSTOMERS} in ${journeySeconds.toFixed(1)}s `
    + `(${(CUSTOMERS / journeySeconds).toFixed(0)} customers/s, ${(journeyRequests / journeySeconds).toFixed(0)} req/s)`)
  console.log(`All requests: ${requestCount} in ${runSeconds.toFixed(1)}s, including sign-up and checks`)
  console.log('Queues after the run:', after.queues.map((q) => `${q.stream} length=${q.length} pending=${q.pending}`))
  console.log('Payments:', after.payments, 'Receipts:', after.receipts)
  console.log(`Errors: ${errors.count}  Consistency violations: ${violations.count}`)
  for (const line of [...errors.examples, ...violations.examples].slice(0, LISTED_PROBLEMS)) {
    console.log(`  - ${line}`)
  }
}

const runId = randomUUID().slice(0, 8)
const merchants = []
const sampled = []
let paymentsSeen = 0
let paymentsCreated = 0
let sampleSize = VERIFY_SAMPLE
let firstCustomer = 0
let pickBank

// Splits n into parts that differ by at most one.
const share = (n, parts, part) => Math.floor(n / parts) + (part < n % parts ? 1 : 0)

function mergeHistogram(into, from) {
  for (let bucket = 0; bucket < from.counts.length; bucket++) {
    into.counts[bucket] += from.counts[bucket]
  }
  into.total += from.total
  into.max = Math.max(into.max, from.max)
}

function mergeProblems(into, from) {
  into.count += from.count
  into.examples.push(...from.examples.slice(0, LISTED_PROBLEMS - into.examples.length))
}

function mergeResult(result) {
  for (const [label, entry] of result.metrics) {
    if (!metrics.has(label)) {
      metrics.set(label, { latency: new Histogram(), statuses: new Map() })
    }
    const local = metrics.get(label)
    mergeHistogram(local.latency, entry.latency)
    for (const [status, n] of entry.statuses) {
      local.statuses.set(status, (local.statuses.get(status) ?? 0) + n)
    }
  }
  for (const [status, n] of result.outcomes) {
    outcomes.set(status, (outcomes.get(status) ?? 0) + n)
  }
  for (const [code, tally] of result.byBank) {
    const local = bankTally(code)
    local.requests += tally.requests
    for (const status of FINAL) {
      local[status] += tally[status]
    }
    mergeHistogram(local.latency, tally.latency)
  }
  mergeProblems(errors, result.errors)
  mergeProblems(violations, result.violations)
  requestCount += result.requestCount
  paymentsCreated += result.paymentsCreated
}

// One worker thread's share of the customers. It reports progress, then checks its sample and sends its measurements.
async function runWorker({ merchantLogins, banks, customers, concurrency, sample, offset }) {
  merchants.push(...merchantLogins)
  pickBank = bankPicker(banks)
  sampleSize = sample
  firstCustomer = offset
  let done = 0
  const progress = () => parentPort.postMessage(
    { type: 'progress', done, requests: requestCount, errors: errors.count, violations: violations.count })
  const ticker = setInterval(progress, 1_000)
  await pool('customer payments', customers, concurrency, customerJourney, () => done++)
  clearInterval(ticker)
  progress()
  parentPort.postMessage({ type: 'journeys-done', requests: requestCount })
  await pool('sample check', sampled.length, Math.min(concurrency, 100), (index) => verify(sampled[index]))
  parentPort.postMessage({
    type: 'result',
    metrics: [...metrics],
    outcomes: [...outcomes],
    byBank: [...byBank],
    errors,
    violations,
    requestCount,
    paymentsCreated,
  })
}

function startWorkers(merchantLogins, banks) {
  const threads = Array.from({ length: WORKERS }, () => ({ done: 0, requests: 0, errors: 0, violations: 0 }))
  const totals = () => threads.reduce((sum, thread) => ({
    done: sum.done + thread.done,
    requests: sum.requests + thread.requests,
    errors: sum.errors + thread.errors,
    violations: sum.violations + thread.violations,
  }), { done: 0, requests: 0, errors: 0, violations: 0 })
  const stopProgress = progressLines('customer payments', CUSTOMERS, totals)
  const journeysStarted = performance.now()
  const journeys = { running: WORKERS, seconds: 0, requests: 0 }
  let offset = 0
  const results = threads.map((thread, part) => {
    const customers = share(CUSTOMERS, WORKERS, part)
    const data = {
      merchantLogins,
      banks,
      customers,
      concurrency: Math.max(1, share(CONCURRENCY, WORKERS, part)),
      sample: share(VERIFY_SAMPLE, WORKERS, part),
      offset,
    }
    offset += customers
    return new Promise((resolve, reject) => {
      const worker = new Worker(new URL(import.meta.url), { argv: process.argv.slice(2), workerData: data })
      worker.on('message', (message) => {
        if (message.type === 'progress') {
          Object.assign(thread, message)
        } else if (message.type === 'journeys-done') {
          journeys.requests += message.requests
          if (--journeys.running === 0) {
            journeys.seconds = (performance.now() - journeysStarted) / 1000
            stopProgress()
          }
        } else if (message.type === 'result') {
          resolve(message)
        }
      })
      worker.on('error', reject)
      worker.on('exit', (code) => reject(new Error(`worker ${part} stopped with code ${code} before reporting`)))
    })
  })
  return Promise.all(results).then((reports) => ({ reports, journeys }))
}

async function main() {
  console.log(`Load test ${runId}: ${MERCHANTS} merchants, ${CUSTOMERS} customers, ${BANKS} banks, `
    + `concurrency ${CONCURRENCY} on ${WORKERS} worker threads`)
  const started = performance.now()
  let signedUp = 0
  const stopSignUp = progressLines('merchant sign-up', MERCHANTS,
    () => ({ done: signedUp, requests: requestCount, errors: errors.count, violations: violations.count }))
  await pool('merchant sign-up', MERCHANTS, SIGNUP_CONCURRENCY, signUp, () => signedUp++)
  stopSignUp()
  if (merchants.length === 0) {
    console.error('No merchant could sign up; is the server running?', errors.examples.slice(0, 3))
    process.exit(1)
  }
  const available = (await call('banks', 'GET', '/simulator/banks', { token: await tokenFor(merchants[0]) })).data
  if (available.length < BANKS) {
    console.error(`The backend has ${available.length} banks, fewer than the ${BANKS} requested`)
    process.exit(1)
  }
  const banks = available.slice(0, BANKS)
  const before = await stats()
  const merchantLogins = merchants.map(({ email, token, expiresAt }) => ({ email, token, expiresAt }))
  const { reports, journeys } = await startWorkers(merchantLogins, banks)
  reports.forEach(mergeResult)
  const after = await statsOnceDrained()
  checkTotals(before, after)
  report((performance.now() - started) / 1000, journeys.seconds, journeys.requests, after, banks)
  process.exit(errors.count > 0 || violations.count > 0 ? 1 : 0)
}

if (isMainThread) {
  await main()
} else {
  await runWorker(workerData)
}
