import { FINAL_ATTEMPT_STATUSES, type AttemptStatus, type PaymentRequestStatus } from '../types'

// A key is created once when the customer presses Pay. Every retry of that request must send the same key.
export function newRequestKey(prefix = 'pay'): string {
  return `${prefix}-${crypto.randomUUID()}`
}

export function isFinal(status: AttemptStatus): boolean {
  return FINAL_ATTEMPT_STATUSES.includes(status)
}

export interface PollOptions {
  intervalMs?: number
  timeoutMs?: number
  signal?: AbortSignal
  onUpdate?: (status: PaymentRequestStatus) => void
}

export async function pollUntilFinal(
  fetchStatus: () => Promise<PaymentRequestStatus>,
  { intervalMs = 400, timeoutMs = 60_000, signal, onUpdate }: PollOptions = {},
): Promise<PaymentRequestStatus> {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    throwIfAborted(signal)
    const status = await fetchStatus()
    onUpdate?.(status)
    if (isFinal(status.status)) {
      return status
    }
    if (Date.now() >= deadline) {
      throw new Error('Timed out waiting for the payment request to finish')
    }
    await wait(intervalMs, signal)
  }
}

function wait(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener(
      'abort',
      () => {
        clearTimeout(timer)
        reject(new DOMException('Polling was cancelled', 'AbortError'))
      },
      { once: true },
    )
  })
}

function throwIfAborted(signal?: AbortSignal) {
  if (signal?.aborted) {
    throw new DOMException('Polling was cancelled', 'AbortError')
  }
}
