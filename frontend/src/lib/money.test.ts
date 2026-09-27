import { describe, expect, it } from 'vitest'
import { formatMinor, toMinorUnits } from './money'

describe('toMinorUnits', () => {
  it.each([
    ['249.99', 24999],
    ['10', 1000],
    ['10.5', 1050],
    ['0.01', 1],
    [' 7.25 ', 725],
    ['1000000', 100_000_000],
  ])('converts %s rupees to %i paise without floating-point error', (input, expected) => {
    expect(toMinorUnits(input)).toBe(expected)
  })

  it.each(['', '0', '0.00', '1.005', '-5', 'abc', '1,000', '1e3', '1000000.01', '10000000'])(
    'rejects %s',
    (input) => {
      expect(toMinorUnits(input)).toBeNull()
    },
  )
})

describe('formatMinor', () => {
  it('formats paise as rupees', () => {
    expect(formatMinor(24999, 'INR')).toContain('249.99')
  })
})
