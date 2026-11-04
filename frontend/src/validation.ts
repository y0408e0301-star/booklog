// 入力の確認（機能定義書 FN-01〜FN-03 4 章）。API でも同じことを確かめる。機能を作るたびに足す

export const USERNAME_MAX = 15
export const DISPLAY_NAME_MAX = 30
export const PASSWORD_MAX = 64
// 書籍検索の文字（FN-02 4 章）
export const SEARCH_QUERY_MAX = 100
// レビューの感想（FN-03 RV-4）
export const REVIEW_BODY_MAX = 2000

// 文字数は Unicode の文字（コードポイント）で数える（画面設計書 2.12）。API の数え方と同じ
export function countChars(text: string): number {
  return Array.from(text).length
}

// text を max 文字（コードポイント）までにし、超えた分は入力できないようにする。
// 入力欄の maxLength は UTF-16 の長さで数える（絵文字 1 文字を 2 と数える）ので使わない
export function limitChars(text: string, max: number): string {
  return countChars(text) > max ? Array.from(text).slice(0, max).join('') : text
}

// トリムした後の値を渡す。3〜15 文字の半角英数字と「_」（A-2）
export function isValidUsername(username: string): boolean {
  return /^[A-Za-z0-9_]{3,15}$/.test(username)
}

// トリムしない。8〜64 文字の半角文字（英字・数字・記号・空白）で、英字と数字を両方含む（A-3）
export function isValidPassword(password: string): boolean {
  return /^[\x20-\x7E]{8,64}$/.test(password) && /[A-Za-z]/.test(password) && /\d/.test(password)
}

// トリムした後の値を渡す。1〜30 文字（A-4）
export function isValidDisplayName(displayName: string): boolean {
  const length = countChars(displayName)
  return length >= 1 && length <= DISPLAY_NAME_MAX
}

// 感想は改行を LF にそろえてトリムした後で数える（FN-03 4 章。API と同じ）
export function normalizeBody(body: string): string {
  return body.replace(/\r\n?/g, '\n').trim()
}

// 感想の残りの文字数（RV-4）。負の数なら送れない
export function remainingReviewChars(body: string): number {
  return REVIEW_BODY_MAX - countChars(normalizeBody(body))
}

// ---- 写真（FN-04 PH-1〜PH-3）

export const PHOTOS_MAX = 4
export const PHOTO_BYTES_MAX = 5 * 1024 * 1024
export const PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp']
const PHOTO_EXTENSIONS = /\.(jpe?g|png|webp)$/i

// 画面では拡張子と File.type で確かめる。ファイルの中身（先頭のバイト）は API が確かめる
export function isAcceptablePhoto(file: File): boolean {
  return file.size <= PHOTO_BYTES_MAX && PHOTO_TYPES.includes(file.type) && PHOTO_EXTENSIONS.test(file.name)
}

// 選んだ写真を今の写真の後ろに足す。current は編集で残す写真の数（kept）も含めて数える。
// 添えられない写真は外し（M-23）、合わせて 5 枚以上なら先に選んだものから 4 枚までにする（M-22）
export function addPhotos(current: File[], picked: File[], kept = 0): { photos: File[], error: 'M22' | 'M23' | null } {
  const acceptable = picked.filter(isAcceptablePhoto)
  const all = [...current, ...acceptable]
  const room = PHOTOS_MAX - kept
  if (all.length > room) return { photos: all.slice(0, Math.max(room, 0)), error: 'M22' }
  return { photos: all, error: acceptable.length < picked.length ? 'M23' : null }
}
