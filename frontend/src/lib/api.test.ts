import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApi } from './api'

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(body === null ? '' : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

describe('api client', () => {
  it('sends the bearer token and the idempotency key with a payment request', async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(202, { status: 'QUEUED' }, { 'Idempotent-Replayed': 'false' }))
    const api = createApi('token-123', { fetchImpl })

    const response = await api.submitPaymentRequest('payment-1', 'pay-abc12345', 'APPROVE')

    expect(response.status).toBe(202)
    expect(response.headers.get('Idempotent-Replayed')).toBe('false')
    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe('/api/v1/payments/payment-1/attempts')
    expect(init.method).toBe('POST')
    expect(init.headers).toMatchObject({
      Authorization: 'Bearer token-123',
      'Idempotency-Key': 'pay-abc12345',
      'Content-Type': 'application/json',
    })
    expect(JSON.parse(init.body as string)).toEqual({ scenario: 'APPROVE' })
  })

  it('turns a problem-detail error into an ApiError', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(409, { title: 'Conflict', status: 409, detail: 'The payment has already succeeded' }),
    )
    const api = createApi('token-123', { fetchImpl })

    const error = await api.cancelPayment('payment-1').catch((err: unknown) => err)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 409, title: 'Conflict', message: 'The payment has already succeeded' })
  })

  it('signs out when an authenticated request is rejected with 401', async () => {
    const onUnauthorized = vi.fn()
    const api = createApi('expired-token', { fetchImpl: vi.fn(async () => jsonResponse(401, null)), onUnauthorized })

    await expect(api.me()).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('does not treat a failed sign-in as an expired session', async () => {
    const onUnauthorized = vi.fn()
    const api = createApi(null, {
      fetchImpl: vi.fn(async () => jsonResponse(401, { detail: 'Email or password is incorrect' })),
      onUnauthorized,
    })

    await expect(api.login({ email: 'a@example.com', password: 'wrong' })).rejects.toThrow(
      'Email or password is incorrect',
    )
    expect(onUnauthorized).not.toHaveBeenCalled()
  })
})
