import { describe, expect, it } from 'vitest'
import { fullTime, relativeTime } from './time'

// 画面設計書 2.13
describe('relativeTime', () => {
  const now = new Date('2026-10-16T06:30:00Z')

  it('24 時間未満は経過した時間で出す', () => {
    expect(relativeTime('2026-10-16T06:29:01Z', now)).toBe('たった今')
    expect(relativeTime('2026-10-16T06:29:00Z', now)).toBe('1 分前')
    expect(relativeTime('2026-10-16T05:30:01Z', now)).toBe('59 分前')
    expect(relativeTime('2026-10-16T05:30:00Z', now)).toBe('1 時間前')
    expect(relativeTime('2026-10-15T06:30:01Z', now)).toBe('23 時間前')
  })

  it('24 時間以上は日本時間の日付で出し、前の年以前は年も出す', () => {
    expect(relativeTime('2026-10-15T06:30:00Z', now)).toBe('10月15日')
    // UTC では 12 月 31 日だが、日本時間では 1 月 1 日
    expect(relativeTime('2025-12-31T15:00:00Z', now)).toBe('1月1日')
    expect(relativeTime('2025-12-31T14:59:00Z', now)).toBe('2025年12月31日')
  })
})

describe('fullTime', () => {
  it('日本時間の 24 時間表記', () => {
    expect(fullTime('2026-10-16T06:30:00Z')).toBe('2026/10/16 15:30')
    expect(fullTime('2026-10-16T15:05:00Z')).toBe('2026/10/17 00:05')
  })
})
