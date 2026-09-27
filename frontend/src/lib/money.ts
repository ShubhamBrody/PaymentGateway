const AMOUNT_PATTERN = /^\d{1,7}(\.\d{1,2})?$/
const MAX_MINOR_UNITS = 100_000_000

// Parses a rupee amount such as "249.99" into paise without floating-point arithmetic.
export function toMinorUnits(input: string): number | null {
  const trimmed = input.trim()
  if (!AMOUNT_PATTERN.test(trimmed)) {
    return null
  }
  const [whole, fraction = ''] = trimmed.split('.')
  const minor = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  return minor > 0 && minor <= MAX_MINOR_UNITS ? minor : null
}

export function formatMinor(minor: number, currency: string): string {
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency }).format(minor / 100)
}
