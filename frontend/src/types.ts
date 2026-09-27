export type PaymentStatus = 'AWAITING_PAYMENT' | 'PROCESSING' | 'SUCCEEDED' | 'CANCELLED'

export type AttemptStatus = 'QUEUED' | 'PENDING' | 'SUCCEEDED' | 'DECLINED' | 'FAILED'

export type BankScenario =
  | 'APPROVE'
  | 'DECLINE'
  | 'SLOW_APPROVE'
  | 'TIMEOUT_THEN_APPROVE'
  | 'DUPLICATE_REPLY'
  | 'NO_REPLY'
  | 'RANDOM'

export const PAYMENT_STATUSES: PaymentStatus[] = ['AWAITING_PAYMENT', 'PROCESSING', 'SUCCEEDED', 'CANCELLED']

export const FINAL_ATTEMPT_STATUSES: AttemptStatus[] = ['SUCCEEDED', 'DECLINED', 'FAILED']

export const BANK_SCENARIOS: { value: BankScenario; label: string }[] = [
  { value: 'APPROVE', label: 'Bank approves' },
  { value: 'DECLINE', label: 'Bank declines' },
  { value: 'SLOW_APPROVE', label: 'Bank approves slowly' },
  { value: 'TIMEOUT_THEN_APPROVE', label: 'First request lost, retry approved' },
  { value: 'DUPLICATE_REPLY', label: 'Bank replies twice' },
  { value: 'NO_REPLY', label: 'Bank never replies' },
  { value: 'RANDOM', label: 'Random (load-test mix)' },
]

export interface AuthResponse {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

export interface Merchant {
  id: string
  businessName: string
  email: string
  createdAt: string
}

export interface Payment {
  id: string
  amount: number
  currency: string
  status: PaymentStatus
  description: string | null
  orderReference: string | null
  createdAt: string
  updatedAt: string
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface PaymentAttempt {
  id: string
  paymentId: string
  idempotencyKey: string
  scenario: BankScenario
  status: AttemptStatus
  declineReason: string | null
  bankReference: string | null
  bankRequestCount: number
  receiptNumber: string | null
  createdAt: string
  resolvedAt: string | null
}

export interface PaymentRequestStatus {
  attemptId: string
  paymentId: string
  idempotencyKey: string
  scenario: BankScenario
  status: AttemptStatus
  declineReason: string | null
  bankRequestCount: number
  receiptNumber: string | null
  updatedAt: string
  source: 'cache' | 'database'
}

export interface Receipt {
  receiptNumber: string
  paymentId: string
  attemptId: string
  amount: number
  currency: string
  bankReference: string
  issuedAt: string
}

export interface QueueStats {
  stream: string
  length: number
  pending: number
}

export interface SystemStats {
  queues: QueueStats[]
  payments: Record<PaymentStatus, number>
  attempts: Record<AttemptStatus, number>
  receipts: number
  generatedAt: string
}
