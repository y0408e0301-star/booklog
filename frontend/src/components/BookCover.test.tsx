import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import BookCover from './BookCover'

// 本の表紙（画面設計書 2.9、FN-02 B-7）
describe('BookCover', () => {
  it('G-8 の本棚では列の幅いっぱいに出す', () => {
    render(<BookCover title="こころ" thumbnailUrl={null} width="full" />)
    expect(screen.getByText('表紙なし')).toHaveStyle({ width: '100%' })
  })

  it('表紙の URL があれば、幅の 1.5 倍の高さで出す', () => {
    render(<BookCover title="こころ" thumbnailUrl="https://books.google.com/a" width={64} />)
    const img = screen.getByRole('img', { name: 'こころの表紙' })
    expect(img).toHaveAttribute('src', 'https://books.google.com/a')
    expect(img).toHaveStyle({ width: '64px', height: '96px' })
  })

  // TC-10-3
  // B-7
  it('URL がなければ「表紙なし」の枠を出す', () => {
    render(<BookCover title="こころ" thumbnailUrl={null} width={128} />)
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('表紙なし')).toHaveStyle({ width: '128px', height: '192px' })
  })

  it('画像を読めなかったら「表紙なし」に替え、URL が変わったらまた画像を出す', () => {
    const { rerender } = render(<BookCover title="こころ" thumbnailUrl="https://books.google.com/old" width={48} />)
    fireEvent.error(screen.getByRole('img'))
    expect(screen.getByText('表紙なし')).toBeInTheDocument()

    rerender(<BookCover title="それから" thumbnailUrl="https://books.google.com/new" width={48} />)
    expect(screen.getByRole('img', { name: 'それからの表紙' })).toBeInTheDocument()
  })
})
