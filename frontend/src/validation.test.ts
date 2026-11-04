import { describe, expect, it } from 'vitest'
import {
  addPhotos, countChars, isAcceptablePhoto, isValidDisplayName, isValidPassword, isValidUsername, limitChars,
  normalizeBody, PHOTO_BYTES_MAX, remainingReviewChars,
} from './validation'

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

// FN-03 RV-4・4 章
describe('normalizeBody', () => {
  it('改行を LF にそろえてトリムする', () => {
    expect(normalizeBody('  一\r\n二\r三\n  ')).toBe('一\n二\n三')
  })
})

describe('remainingReviewChars', () => {
  // TC-03-2
  it('2,000 文字から、そろえた感想の文字数を引く', () => {
    expect(remainingReviewChars('')).toBe(2000)
    expect(remainingReviewChars('😀'.repeat(2000))).toBe(0)
    expect(remainingReviewChars('あ'.repeat(2001))).toBe(-1)
    // CRLF も 1 文字と数える
    expect(remainingReviewChars('あ\r\nい')).toBe(1997)
  })
})

// 写真（FN-04 4 章、TC-04-1〜TC-04-3）
const file = (name: string, type: string, size = 10) => new File([new Uint8Array(size)], name, { type })
const jpeg = (name = 'a.jpg') => file(name, 'image/jpeg')

describe('isAcceptablePhoto', () => {
  it('5MB までの JPEG・PNG・WebP だけを添えられる', () => {
    expect(isAcceptablePhoto(file('a.jpeg', 'image/jpeg', PHOTO_BYTES_MAX))).toBe(true)
    expect(isAcceptablePhoto(file('a.PNG', 'image/png'))).toBe(true)
    expect(isAcceptablePhoto(file('a.webp', 'image/webp'))).toBe(true)
    expect(isAcceptablePhoto(file('a.jpg', 'image/jpeg', PHOTO_BYTES_MAX + 1))).toBe(false)
    expect(isAcceptablePhoto(file('a.gif', 'image/gif'))).toBe(false)
    // 拡張子だけ変えたもの・種類だけ合っているもの
    expect(isAcceptablePhoto(file('a.jpg', 'text/plain'))).toBe(false)
    expect(isAcceptablePhoto(file('a.txt', 'image/jpeg'))).toBe(false)
  })
})

describe('addPhotos', () => {
  it('今の写真の後ろに足す', () => {
    const [a, b] = [jpeg('a.jpg'), jpeg('b.jpg')]
    expect(addPhotos([a], [b])).toEqual({ photos: [a, b], error: null })
  })

  it('一度に 6 枚選ぶと、先の 4 枚だけを残して M-22', () => {
    const picked = [1, 2, 3, 4, 5, 6].map((i) => jpeg(`${i}.jpg`))
    expect(addPhotos([], picked)).toEqual({ photos: picked.slice(0, 4), error: 'M22' })
  })

  it('編集で残す写真が 3 枚あれば、足せるのは 1 枚まで', () => {
    const picked = [jpeg('a.jpg'), jpeg('b.jpg')]
    expect(addPhotos([], picked, 3)).toEqual({ photos: picked.slice(0, 1), error: 'M22' })
  })

  it('添えられない写真は外して M-23', () => {
    const ok = jpeg()
    expect(addPhotos([], [ok, file('a.gif', 'image/gif')])).toEqual({ photos: [ok], error: 'M23' })
  })
})
