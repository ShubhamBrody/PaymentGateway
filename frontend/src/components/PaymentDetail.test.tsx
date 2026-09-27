import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Api, ApiResponse } from '../lib/api'
import type { AttemptStatus, Payment, PaymentAttempt, PaymentRequestStatus, Receipt } from '../types'
import { PaymentDetail } from './PaymentDetail'

function ok<T>(data: T, headers: Record<string, string> = {}): Promise<ApiResponse<T>> {
  return Promise.resolve({ data, status: 200, headers: new Headers(headers) })
}

// A fake server: remembers each idempotency key, replays it on retry, and finishes after one poll.
function fakeApi() {
  const payment: Payment = {
    id: 'payment-1',
    amount: 24999,
    currency: 'INR',
    status: 'AWAITING_PAYMENT',
    description: 'Headphones',
    orderReference: 'order-42',
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
  const requests = new Map<string, PaymentRequestStatus>()
  const attempts: PaymentAttempt[] = []
  const receipt: Receipt = {
    receiptNumber: 'RCPT-0123456789AB',
    paymentId: 'payment-1',
    attemptId: 'attempt-1',
    amount: 24999,
    currency: 'INR',
    bankReference: 'BNK-1',
    issuedAt: new Date().toISOString(),
  }

  const finish = (key: string) => {
    const request = requests.get(key)!
    const done: PaymentRequestStatus = { ...request, status: 'SUCCEEDED' as AttemptStatus, receiptNumber: receipt.receiptNumber }
    requests.set(key, done)
    payment.status = 'SUCCEEDED'
    attempts[0] = { ...attempts[0], status: 'SUCCEEDED', receiptNumber: receipt.receiptNumber }
    return done
  }

  const api = {
    getPayment: vi.fn(() => ok({ ...payment })),
    listAttempts: vi.fn(() => ok([...attempts])),
    getReceipt: vi.fn(() => ok(receipt)),
    cancelPayment: vi.fn(() => ok({ ...payment, status: 'CANCELLED' })),
    submitPaymentRequest: vi.fn((_paymentId: string, key: string, scenario: PaymentAttempt['scenario']) => {
      const existing = requests.get(key)
      if (existing) {
        return ok(existing, { 'Idempotent-Replayed': 'true' })
      }
      const created: PaymentRequestStatus = {
        attemptId: 'attempt-1',
        paymentId: 'payment-1',
        idempotencyKey: key,
        scenario,
        status: 'QUEUED',
        declineReason: null,
        bankRequestCount: 0,
        receiptNumber: null,
        updatedAt: new Date().toISOString(),
        source: 'database',
      }
      requests.set(key, created)
      payment.status = 'PROCESSING'
      attempts.push({
        id: 'attempt-1',
        paymentId: 'payment-1',
        idempotencyKey: key,
        scenario,
        status: 'QUEUED',
        declineReason: null,
        bankReference: null,
        bankRequestCount: 0,
        receiptNumber: null,
        createdAt: new Date().toISOString(),
        resolvedAt: null,
      })
      return ok(created, { 'Idempotent-Replayed': 'false' })
    }),
    getPaymentRequestStatus: vi.fn((_paymentId: string, key: string) => ok(finish(key))),
  }
  return { api: api as unknown as Api, calls: api }
}

describe('PaymentDetail', () => {
  it('pays through the queue, shows the receipt, and retries with the same idempotency key', async () => {
    const user = userEvent.setup()
    const { api, calls } = fakeApi()
    render(<PaymentDetail api={api} paymentId="payment-1" pollIntervalMs={1} />)

    await user.click(await screen.findByRole('button', { name: 'Pay' }))

    expect(await screen.findByTestId('receipt-number')).toHaveTextContent('RCPT-0123456789AB')
    expect(screen.getByTestId('request-status')).toHaveTextContent('SUCCEEDED')
    expect(screen.getByTestId('replayed')).toHaveTextContent('No')
    const key = screen.getByTestId('request-key').textContent

    await user.click(screen.getByRole('button', { name: 'Retry same request' }))

    await waitFor(() => expect(screen.getByTestId('replayed')).toHaveTextContent('Yes'))
    expect(calls.submitPaymentRequest).toHaveBeenCalledTimes(2)
    expect(calls.submitPaymentRequest.mock.calls[0][1]).toBe(key)
    expect(calls.submitPaymentRequest.mock.calls[1][1]).toBe(key)
    expect(screen.getAllByRole('row')).toHaveLength(2)
  })

  it('does not offer Pay once the payment is no longer awaiting payment', async () => {
    const { api } = fakeApi()
    const user = userEvent.setup()
    render(<PaymentDetail api={api} paymentId="payment-1" pollIntervalMs={1} />)

    await user.click(await screen.findByRole('button', { name: 'Pay' }))
    await screen.findByTestId('receipt-number')

    expect(screen.getByRole('button', { name: 'Pay' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel payment' })).toBeDisabled()
  })
})
