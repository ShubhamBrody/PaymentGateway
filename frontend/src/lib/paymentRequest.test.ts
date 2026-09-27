import { describe, expect, it, vi } from 'vitest'
import type { AttemptStatus, PaymentRequestStatus } from '../types'
import { isFinal, newRequestKey, pollUntilFinal } from './paymentRequest'

// Same rule the server enforces on the Idempotency-Key header for payment requests.
const SERVER_KEY_PATTERN = /^[A-Za-z0-9_-]{8,64}$/

function status(value: AttemptStatus): PaymentRequestStatus {
  return {
    attemptId: 'attempt-1',
    paymentId: 'payment-1',
    idempotencyKey: 'pay-1',
    scenario: 'APPROVE',
    status: value,
    declineReason: null,
    bankRequestCount: 1,
    receiptNumber: value === 'SUCCEEDED' ? 'RCPT-1' : null,
    updatedAt: new Date().toISOString(),
    source: 'cache',
  }
}

describe('newRequestKey', () => {
  it('creates unique keys the server accepts', () => {
    const keys = new Set(Array.from({ length: 200 }, () => newRequestKey()))
    expect(keys.size).toBe(200)
    for (const key of keys) {
      expect(key).toMatch(SERVER_KEY_PATTERN)
    }
  })
})

describe('isFinal', () => {
  it.each<[AttemptStatus, boolean]>([
    ['QUEUED', false],
    ['PENDING', false],
    ['SUCCEEDED', true],
    ['DECLINED', true],
    ['FAILED', true],
  ])('%s final is %s', (value, expected) => {
    expect(isFinal(value)).toBe(expected)
  })
})

describe('pollUntilFinal', () => {
  it('polls until the request is final and reports every update', async () => {
    const fetchStatus = vi
      .fn<() => Promise<PaymentRequestStatus>>()
      .mockResolvedValueOnce(status('QUEUED'))
      .mockResolvedValueOnce(status('PENDING'))
      .mockResolvedValueOnce(status('SUCCEEDED'))
    const updates: AttemptStatus[] = []

    const result = await pollUntilFinal(fetchStatus, { intervalMs: 1, onUpdate: (s) => updates.push(s.status) })

    expect(result.status).toBe('SUCCEEDED')
    expect(updates).toEqual(['QUEUED', 'PENDING', 'SUCCEEDED'])
    expect(fetchStatus).toHaveBeenCalledTimes(3)
  })

  it('gives up after the timeout', async () => {
    const fetchStatus = vi.fn(async () => status('PENDING'))

    await expect(pollUntilFinal(fetchStatus, { intervalMs: 1, timeoutMs: 10 })).rejects.toThrow('Timed out')
  })

  it('stops when cancelled', async () => {
    const controller = new AbortController()
    const fetchStatus = vi.fn(async () => {
      controller.abort()
      return status('PENDING')
    })

    await expect(pollUntilFinal(fetchStatus, { intervalMs: 50, signal: controller.signal })).rejects.toMatchObject({
      name: 'AbortError',
    })
    expect(fetchStatus).toHaveBeenCalledTimes(1)
  })
})
