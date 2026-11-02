import { describe, expect, it } from 'vitest'
import { formatPublishedDate, publisherAndDate } from './format'

// 画面設計書 2.13
describe('formatPublishedDate', () => {
  it('年・年月・年月日の形で出す', () => {
    expect(formatPublishedDate('2024')).toBe('2024')
    expect(formatPublishedDate('2024-05')).toBe('2024年5月')
    expect(formatPublishedDate('2024-05-10')).toBe('2024年5月10日')
    expect(formatPublishedDate('2003-12-01')).toBe('2003年12月1日')
  })
})

describe('publisherAndDate', () => {
  it('ない項目は出さない', () => {
    expect(publisherAndDate('新潮社', '2003-06')).toBe('新潮社 · 2003年6月')
    expect(publisherAndDate('新潮社', null)).toBe('新潮社')
    expect(publisherAndDate(null, '2003')).toBe('2003')
    expect(publisherAndDate(null, null)).toBeNull()
  })
})
