import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import type { ReviewPhoto } from '../api'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import { ToastProvider } from './ToastProvider'
import ReviewPhotos from './ReviewPhotos'

// 投稿カードの写真（画面設計書 2.7、FN-04 PH-6〜PH-8）。15 分で切れる署名付き URL の取り直し

const future = '2999-01-01T00:00:00Z'
const past = '2000-01-01T00:00:00Z'

const photo = (id: number, expiresAt = future, url = `https://s3/old/${id}.png`): ReviewPhoto => ({ id, url, expiresAt })

function renderPhotos(photos: ReviewPhoto[]) {
  return render(<ToastProvider><ReviewPhotos photos={photos} /></ToastProvider>)
}

const cells = () => screen.getAllByRole('button', { name: /を拡大/ })
const thumbnails = () => cells().map((cell) => cell.querySelector('img')!)

describe('ReviewPhotos', () => {
  it('写真がなければ何も出さない', () => {
    const { container } = renderPhotos([])
    expect(container).toBeEmptyDOMElement()
  })

  it('選んだ順に並べ、何枚目かを読み上げる', () => {
    renderPhotos([photo(1), photo(2), photo(3)])
    expect(cells().map((c) => c.getAttribute('aria-label'))).toEqual(['写真 1 / 3 を拡大', '写真 2 / 3 を拡大', '写真 3 / 3 を拡大'])
    expect(thumbnails().map((img) => img.getAttribute('src'))).toEqual(
      ['https://s3/old/1.png', 'https://s3/old/2.png', 'https://s3/old/3.png'])
  })

  it('押すと G-11 でその写真を大きく出す', async () => {
    renderPhotos([photo(1), photo(2)])
    await userEvent.click(cells()[1])
    expect(screen.getByRole('dialog', { name: '写真の拡大' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '写真 2 / 2' })).toBeInTheDocument()
  })

  // TC-04-9
  it('読み込めなければ URL を取り直し、同時にいくつ失敗しても送るのは 1 回だけ', async () => {
    const fetch = mockFetch({
      'POST /api/photos/urls': () => json(200, { items: [photo(1, future, 'https://s3/new/1.png'), photo(2, future, 'https://s3/new/2.png')] }),
    })
    renderPhotos([photo(1), photo(2)])
    thumbnails().forEach((img) => fireEvent.error(img))

    await waitFor(() => expect(thumbnails()[0]).toHaveAttribute('src', 'https://s3/new/1.png'))
    expect(thumbnails()[1]).toHaveAttribute('src', 'https://s3/new/2.png')
    expect(fetch.keys()).toEqual(['POST /api/photos/urls'])
    expect(sentJson(fetch)).toEqual({ photoIds: [1, 2] })
  })

  it('取り直しの結果に入っていない写真があれば（削除された）、「写真を読み込めませんでした」を出す', async () => {
    mockFetch({ 'POST /api/photos/urls': () => json(200, { items: [photo(1)] }) })
    renderPhotos([photo(1), photo(2)])
    fireEvent.error(thumbnails()[0])
    expect(await screen.findByText('写真を読み込めませんでした')).toBeInTheDocument()
  })

  it('取り直しに失敗したら「写真を読み込めませんでした」を出す', async () => {
    mockFetch({ 'POST /api/photos/urls': () => json(500, { code: 'INTERNAL_ERROR', message: '…' }) })
    renderPhotos([photo(1)])
    fireEvent.error(thumbnails()[0])
    expect(await screen.findByText('写真を読み込めませんでした')).toBeInTheDocument()
  })

  // TC-04-10
  it('取り直した期限内の URL でも読み込めなければ、もう取り直さない', async () => {
    const fetch = mockFetch({ 'POST /api/photos/urls': () => json(200, { items: [photo(1, future, 'https://s3/new/1.png')] }) })
    renderPhotos([photo(1)])
    fireEvent.error(thumbnails()[0])
    await waitFor(() => expect(thumbnails()[0]).toHaveAttribute('src', 'https://s3/new/1.png'))

    fireEvent.error(thumbnails()[0])
    expect(await screen.findByText('写真を読み込めませんでした')).toBeInTheDocument()
    expect(fetch.keys()).toHaveLength(1)
  })

  // TC-04-8
  it('期限の切れた写真は、G-11 を開く前に取り直す', async () => {
    const fetch = mockFetch({ 'POST /api/photos/urls': () => json(200, { items: [photo(1, future, 'https://s3/new/1.png')] }) })
    renderPhotos([photo(1, past)])
    await userEvent.click(cells()[0])
    expect(await screen.findByRole('img', { name: '写真 1 / 1' })).toHaveAttribute('src', 'https://s3/new/1.png')
    expect(fetch.keys()).toEqual(['POST /api/photos/urls'])
  })

  it('投稿が読み直されて写真が変わったら、初めの URL に戻す', async () => {
    mockFetch({ 'POST /api/photos/urls': () => json(200, { items: [photo(1, future, 'https://s3/new/1.png')] }) })
    const { rerender } = renderPhotos([photo(1)])
    fireEvent.error(thumbnails()[0])
    await waitFor(() => expect(thumbnails()[0]).toHaveAttribute('src', 'https://s3/new/1.png'))

    rerender(<ToastProvider><ReviewPhotos photos={[photo(1, future, 'https://s3/reloaded/1.png')]} /></ToastProvider>)
    expect(thumbnails()[0]).toHaveAttribute('src', 'https://s3/reloaded/1.png')
  })
})
