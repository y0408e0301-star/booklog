import { describe, expect, it } from 'vitest'
import { countChars, isValidDisplayName, isValidPassword, isValidUsername, limitChars } from './validation'

// 文字数の数え方（画面設計書 2.12）。API と同じくコードポイントで数える
describe('countChars', () => {
  it('絵文字・漢字も 1 文字と数える', () => {
    expect(countChars('')).toBe(0)
    expect(countChars('abc')).toBe(3)
    expect(countChars('吾輩は猫である')).toBe(7)
    // 「😀」は JavaScript の文字列では長さ 2（サロゲートペア）
    expect('😀'.length).toBe(2)
    expect(countChars('😀😀')).toBe(2)
  })

  it('改行も 1 文字と数える', () => {
    expect(countChars('a\nb')).toBe(3)
  })
})

// FN-01 10 章のテストの観点
describe('isValidUsername', () => {
  // TC-01-1
  it('3〜15 文字の半角英数字と「_」', () => {
    expect(isValidUsername('ab')).toBe(false)
    expect(isValidUsername('abc')).toBe(true)
    expect(isValidUsername('a'.repeat(15))).toBe(true)
    expect(isValidUsername('a'.repeat(16))).toBe(false)
    expect(isValidUsername('Taro_1')).toBe(true)
    expect(isValidUsername('taro-y')).toBe(false)
    expect(isValidUsername('たろう')).toBe(false)
    expect(isValidUsername('ｔａｒｏ')).toBe(false)
  })
})

describe('isValidPassword', () => {
  // TC-01-2
  it('8〜64 文字の半角文字で、英字と数字を両方含む', () => {
    expect(isValidPassword('passwo1')).toBe(false)
    expect(isValidPassword('passwor1')).toBe(true)
    expect(isValidPassword(`1${'a'.repeat(63)}`)).toBe(true)
    expect(isValidPassword(`1${'a'.repeat(64)}`)).toBe(false)
    expect(isValidPassword('password')).toBe(false)
    expect(isValidPassword('12345678')).toBe(false)
    expect(isValidPassword('passwor1あ')).toBe(false)
    // 前後の空白も取り除かずにそのまま使う
    expect(isValidPassword(' pass word1 ')).toBe(true)
  })
})

describe('isValidDisplayName', () => {
  it('1〜30 文字', () => {
    expect(isValidDisplayName('')).toBe(false)
    expect(isValidDisplayName('た')).toBe(true)
    expect(isValidDisplayName('あ'.repeat(30))).toBe(true)
    expect(isValidDisplayName('あ'.repeat(31))).toBe(false)
  })

  it('絵文字も 1 文字と数える（API と同じ）', () => {
    expect(isValidDisplayName('😀'.repeat(30))).toBe(true)
    expect(isValidDisplayName('😀'.repeat(31))).toBe(false)
  })
})

describe('limitChars', () => {
  it('上限までの文字（コードポイント）だけを残す', () => {
    expect(limitChars('あ'.repeat(30), 30)).toBe('あ'.repeat(30))
    expect(limitChars('あ'.repeat(31), 30)).toBe('あ'.repeat(30))
    // 絵文字を途中で切らない
    expect(limitChars('😀'.repeat(31), 30)).toBe('😀'.repeat(30))
    expect(limitChars('', 30)).toBe('')
  })
})
