import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { M } from '../messages'
import { renderComponent } from '../test/render'
import SearchBox from './SearchBox'

// ヘッダーの書籍の検索欄（FN-02 B-1、画面設計書 2.6）

const input = () => screen.getByRole('searchbox', { name: '本を探す文字' })
const location = () => screen.getByRole('status', { name: '今の URL' })

describe('SearchBox', () => {
  it('Enter キーで、トリムした文字の G-4 を開く', async () => {
    renderComponent(<SearchBox initialQuery="" autoFocus={false} className="" />)
    await userEvent.type(input(), '  夏目漱石 {Enter}')
    expect(location()).toHaveTextContent('/books/search?q=%E5%A4%8F%E7%9B%AE%E6%BC%B1%E7%9F%B3')
  })

  it('虫めがねのボタンでも開く', async () => {
    renderComponent(<SearchBox initialQuery="" autoFocus={false} className="" />)
    await userEvent.type(input(), '978-4-10-101001-4')
    await userEvent.click(screen.getByRole('button', { name: '本を探す' }))
    expect(location()).toHaveTextContent('/books/search?q=978-4-10-101001-4')
  })

  // TC-02-2
  it('空白だけなら M-12 を出し、検索しない', async () => {
    renderComponent(<SearchBox initialQuery="" autoFocus={false} className="" />, { path: '/books/1' })
    await userEvent.type(input(), '　 {Enter}')
    expect(screen.getByText(M[12])).toBeInTheDocument()
    expect(location()).toHaveTextContent('/books/1')
  })

  it('100 文字（コードポイント）までしか入力できず、絵文字も 1 文字と数える', async () => {
    renderComponent(<SearchBox initialQuery="" autoFocus={false} className="" />)
    await userEvent.click(input())
    await userEvent.paste('😀'.repeat(101))
    expect(Array.from((input() as HTMLInputElement).value)).toHaveLength(100)
  })

  it('G-4 では検索した文字を入れておき、指定されたら入力欄にカーソルを置く', () => {
    renderComponent(<SearchBox initialQuery="夏目漱石" autoFocus className="" />)
    expect(input()).toHaveValue('夏目漱石')
    expect(input()).toHaveFocus()
  })
})
