import { describe, expect, it } from 'vitest'
import { amount, currencySymbol, money, moneyToType, moneyTyped } from './money.ts'

describe('showing money', () => {
  it('writes the amount first and the currency after it', () => {
    expect(money(250_050, 'PLN')).toBe('2,500.50\u00a0zł')
    expect(money(5, 'PLN')).toBe('0.05\u00a0zł')
    expect(money(0, 'PLN')).toBe('0.00\u00a0zł')
    expect(money(120_000, 'EUR')).toBe('1,200.00\u00a0€')
    expect(money(120_000, 'UAH')).toBe('1,200.00\u00a0₴')
  })

  it('is exact where a fraction of a number would not be', () => {
    // 9999999999.99 cannot be held exactly as a fraction; as whole grosze it can.
    expect(money(999_999_999_999, 'PLN')).toBe('9,999,999,999.99\u00a0zł')
    expect(money(1_000_000_000_002, 'PLN')).toBe('10,000,000,000.02\u00a0zł')
    expect(amount(29, 'PLN')).toBe('0.29')
    expect(amount(57, 'PLN')).toBe('0.57')
  })

  it('follows the currency in how many decimals there are', () => {
    expect(money(2_500, 'JPY')).toBe('2,500\u00a0¥')
    expect(moneyToType(2_500, 'JPY')).toBe('2500')
    expect(moneyTyped('2,500', 'JPY')).toBe(2_500)
    expect(moneyTyped('2.5', 'JPY')).toBeNull()
  })

  it('shows what is below zero with a minus', () => {
    expect(money(-12_000, 'PLN')).toBe('−120.00\u00a0zł')
  })

  it('shows a code it does not know as it is', () => {
    expect(money(1_000, 'XYZ1')).toBe('10.00\u00a0XYZ1')
    expect(currencySymbol('PLN')).toBe('zł')
  })

  it('gives a field the plain number', () => {
    expect(moneyToType(250_000, 'PLN')).toBe('2500')
    expect(moneyToType(250_050, 'PLN')).toBe('2500.50')
    expect(moneyToType(5, 'PLN')).toBe('0.05')
  })
})

describe('reading an amount a person typed', () => {
  it.each([
    ['2500', 250_000],
    ['2 500', 250_000],
    ['2\u00a0500,50', 250_050],
    ['2500.5', 250_050],
    ['2500,50', 250_050],
    ['2,500', 250_000],
    ['2.500', 250_000],
    ['2,500.50', 250_050],
    ['2.500,50', 250_050],
    ['1,234,567.89', 123_456_789],
    ['0,05', 5],
    ['0.29', 29],
    ['19.99', 1_999],
    [' 80 ', 8_000],
  ])('%s is %i grosze', (typed, grosze) => {
    expect(moneyTyped(typed, 'PLN')).toBe(grosze)
  })

  it.each(['', 'abc', '12 zł', '-5', '2500.123', '2500.', '1,23,456', '.50', '2..5', '1e3'])('%s is not an amount', (typed) => {
    expect(moneyTyped(typed, 'PLN')).toBeNull()
  })

  it('reads back whatever it wrote', () => {
    for (const grosze of [1, 29, 57, 100, 1_999, 250_050, 999_999_999_999]) {
      expect(moneyTyped(moneyToType(grosze, 'PLN'), 'PLN')).toBe(grosze)
    }
  })
})
