import type {
  AuthResponse,
  BankScenario,
  Merchant,
  Page,
  Payment,
  PaymentAttempt,
  PaymentRequestStatus,
  PaymentStatus,
  Receipt,
  SystemStats,
} from '../types'

export class ApiError extends Error {
  readonly status: number
  readonly title: string

  constructor(status: number, title: string, detail: string) {
    super(detail)
    this.status = status
    this.title = title
  }
}

export interface ApiResponse<T> {
  data: T
  status: number
  headers: Headers
}

interface RequestOptions {
  method?: string
  body?: unknown
  headers?: Record<string, string>
}

export interface ApiDependencies {
  fetchImpl?: typeof fetch
  onUnauthorized?: () => void
}

export async function apiRequest<T>(
  path: string,
  token: string | null,
  options: RequestOptions = {},
  { fetchImpl = fetch, onUnauthorized }: ApiDependencies = {},
): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetchImpl(`/api/v1${path}`, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const payload = parseJson(await response.text())
  if (!response.ok) {
    if (response.status === 401 && token) {
      onUnauthorized?.()
    }
    const problem = (payload ?? {}) as { title?: string; detail?: string }
    throw new ApiError(
      response.status,
      problem.title ?? response.statusText,
      problem.detail ?? `Request failed with status ${response.status}`,
    )
  }
  return { data: payload as T, status: response.status, headers: response.headers }
}

function parseJson(text: string): unknown {
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

export function createApi(token: string | null, dependencies: ApiDependencies = {}) {
  const call = <T>(path: string, options?: RequestOptions) => apiRequest<T>(path, token, options, dependencies)
  return {
    register: (body: { businessName: string; email: string; password: string }) =>
      call<Merchant>('/auth/register', { method: 'POST', body }),
    login: (body: { email: string; password: string }) => call<AuthResponse>('/auth/login', { method: 'POST', body }),
    me: () => call<Merchant>('/merchants/me'),
    createPayment: (
      body: { amount: number; currency: string; description?: string; orderReference?: string },
      idempotencyKey: string,
    ) => call<Payment>('/payments', { method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
    listPayments: (params: { page: number; size: number; status?: PaymentStatus }) => {
      const query = new URLSearchParams({ page: String(params.page), size: String(params.size) })
      if (params.status) {
        query.set('status', params.status)
      }
      return call<Page<Payment>>(`/payments?${query}`)
    },
    getPayment: (paymentId: string) => call<Payment>(`/payments/${paymentId}`),
    cancelPayment: (paymentId: string) => call<Payment>(`/payments/${paymentId}/cancel`, { method: 'POST' }),
    listAttempts: (paymentId: string) => call<PaymentAttempt[]>(`/payments/${paymentId}/attempts`),
    submitPaymentRequest: (paymentId: string, idempotencyKey: string, scenario: BankScenario) =>
      call<PaymentRequestStatus>(`/payments/${paymentId}/attempts`, {
        method: 'POST',
        body: { scenario },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    getPaymentRequestStatus: (paymentId: string, idempotencyKey: string) =>
      call<PaymentRequestStatus>(`/payments/${paymentId}/requests/${encodeURIComponent(idempotencyKey)}`),
    getReceipt: (paymentId: string) => call<Receipt>(`/payments/${paymentId}/receipt`),
    stats: () => call<SystemStats>('/simulator/stats'),
  }
}

export type Api = ReturnType<typeof createApi>
