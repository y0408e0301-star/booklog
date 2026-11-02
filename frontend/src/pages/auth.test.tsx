import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { M } from '../messages'
import { json, mockFetch, sentJson } from '../test/fetchMock'
import { renderComponent } from '../test/render'
import LoginPage from './LoginPage'
import SignupPage from './SignupPage'

// ユーザー登録（G-2）とログイン（G-1）の画面（FN-01）。10 章のテストの観点のうち、画面で確かめるもの

const me = { username: 'taro_y', displayName: 'たろう' }

// API が返すメッセージ（機能定義書 4 章）
const M6 = 'ユーザー ID かパスワードが違います。'
const M7 = 'ログインに続けて失敗したため、15 分間ログインできません。'

const field = (name: string) => screen.getByLabelText(name)

async function fillSignup(values: { username?: string, displayName?: string, password?: string, confirm?: string }) {
  if (values.username) await userEvent.type(field('ユーザー ID'), values.username)
  if (values.displayName) await userEvent.type(field('表示名'), values.displayName)
  if (values.password) await userEvent.type(field('パスワード'), values.password)
  if (values.confirm) await userEvent.type(field('パスワード（確認）'), values.confirm)
}

describe('ユーザー登録の画面（A-1〜A-4）', () => {
  it('M-2〜M-5：条件に合わない項目の下にメッセージを出し、最初の項目にカーソルを置く。API には送らない', async () => {
    const fetch = mockFetch({})
    renderComponent(<SignupPage />, { path: '/signup', auth: { me: null } })
    await fillSignup({ username: 'ab', password: 'password', confirm: 'passwor' })
    await userEvent.click(screen.getByRole('button', { name: '登録する' }))

    expect(screen.getByText(M[2])).toBeInTheDocument()
    expect(screen.getByText(M[5])).toBeInTheDocument()
    expect(screen.getByText(M[3])).toBeInTheDocument()
    expect(screen.getByText(M[4])).toBeInTheDocument()
    expect(field('ユーザー ID')).toHaveFocus()
    expect(field('ユーザー ID')).toHaveAttribute('aria-invalid', 'true')
    expect(fetch.calls).toHaveLength(0)
  })

  it('入力し直すと、その入力欄のメッセージだけを消す', async () => {
    mockFetch({})
    renderComponent(<SignupPage />, { path: '/signup', auth: { me: null } })
    await fillSignup({ username: 'ab', password: 'password1', confirm: 'password2' })
    await userEvent.click(screen.getByRole('button', { name: '登録する' }))
    expect(screen.getByText(M[4])).toBeInTheDocument()

    await userEvent.type(field('パスワード（確認）'), 'x')
    expect(screen.queryByText(M[4])).not.toBeInTheDocument()
    expect(screen.getByText(M[2])).toBeInTheDocument()
  })

  it('M-1：ユーザー ID の入力欄から離れたとき、使われていればメッセージを出す', async () => {
    mockFetch({ 'GET /api/auth/username-availability?username=taro_y': () => json(200, { available: false }) })
    renderComponent(<SignupPage />, { path: '/signup', auth: { me: null } })
    await userEvent.type(field('ユーザー ID'), ' taro_y ')
    await userEvent.tab()
    expect(await screen.findByText(M[1])).toBeInTheDocument()
  })

  it('A-2・A-4：前後の空白を取って送り、パスワードは取らない。登録したら G-3 に移す', async () => {
    const signedIn = vi.fn()
    const fetch = mockFetch({
      'GET /api/auth/username-availability?username=taro_y': () => json(200, { available: true }),
      'POST /api/auth/signup': () => json(201, me),
    })
    renderComponent(<SignupPage />, { path: '/signup', auth: { me: null, signedIn } })
    await fillSignup({ username: ' taro_y ', displayName: ' たろう ', password: ' pass1234 ', confirm: ' pass1234 ' })
    await userEvent.click(screen.getByRole('button', { name: '登録する' }))

    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/$/)
    expect(sentJson(fetch, fetch.keys().indexOf('POST /api/auth/signup'))).toEqual({ username: 'taro_y', displayName: 'たろう', password: ' pass1234 ' })
    expect(signedIn).toHaveBeenCalledWith(me)
  })

  it('M-1：送った後に使われていたら、ユーザー ID の下に API のメッセージを出す', async () => {
    mockFetch({
      'POST /api/auth/signup': () => json(409, { code: 'USERNAME_TAKEN', message: M[1], field: 'username' }),
    })
    renderComponent(<SignupPage />, { path: '/signup', auth: { me: null } })
    await fillSignup({ username: 'taro_y', displayName: 'たろう', password: 'pass1234', confirm: 'pass1234' })
    await userEvent.click(screen.getByRole('button', { name: '登録する' }))

    expect(await screen.findByText(M[1])).toBeInTheDocument()
    expect(field('ユーザー ID')).toHaveFocus()
    expect(screen.getByRole('button', { name: '登録する' })).toBeEnabled()
  })
})

async function submitLogin(username: string, password: string) {
  await userEvent.type(field('ユーザー ID'), username)
  await userEvent.type(field('パスワード'), password)
  await userEvent.click(screen.getByRole('button', { name: 'ログイン' }))
}

describe('ログインの画面（A-5〜A-7）', () => {
  it('M-6：ユーザー ID かパスワードが違うときは、どちらが違うかを示さずにフォームの上に出す', async () => {
    mockFetch({
      'POST /api/auth/login': () => json(401, { code: 'LOGIN_FAILED', message: M6, field: null }),
    })
    renderComponent(<LoginPage />, { path: '/login', auth: { me: null } })
    await submitLogin('taro_y', 'wrong1234')

    expect(await screen.findByRole('alert')).toHaveTextContent(M6)
    expect(screen.getByRole('button', { name: 'ログイン' })).toBeEnabled()
  })

  it('M-7：続けて失敗して止められているときは、そのメッセージを出す', async () => {
    mockFetch({
      'POST /api/auth/login': () => json(423, { code: 'LOGIN_LOCKED', message: M7, field: null }),
    })
    renderComponent(<LoginPage />, { path: '/login', auth: { me: null } })
    await submitLogin('taro_y', 'pass1234')

    expect(await screen.findByRole('alert')).toHaveTextContent(M7)
  })

  it('次に「ログイン」を押したときに、前のメッセージを消す', async () => {
    mockFetch({
      'POST /api/auth/login': [
        () => json(401, { code: 'LOGIN_FAILED', message: M6, field: null }),
        () => new Promise<Response>(() => {}),
      ],
    })
    renderComponent(<LoginPage />, { path: '/login', auth: { me: null } })
    await submitLogin('taro_y', 'wrong1234')
    await screen.findByRole('alert')
    await userEvent.click(screen.getByRole('button', { name: 'ログイン' }))

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ログイン中…' })).toBeDisabled()
  })

  it('ユーザー ID・パスワードのどちらかが空のうちは押せない', async () => {
    mockFetch({})
    renderComponent(<LoginPage />, { path: '/login', auth: { me: null } })
    await userEvent.type(field('ユーザー ID'), 'taro_y')
    expect(screen.getByRole('button', { name: 'ログイン' })).toBeDisabled()
  })
})

describe('ログインした後の移り先（A-8・A-9）', () => {
  // TC-01-6
  it('ログインしていないときに開こうとした画面があれば、ログインした後にそこへ移す', async () => {
    mockFetch({ 'POST /api/auth/login': () => json(200, me) })
    renderComponent(<LoginPage />, {
      path: { pathname: '/login', state: { from: { pathname: '/books/1', search: '?x=1', hash: '' } } },
      auth: { me: null },
    })
    await submitLogin('taro_y', 'pass1234')
    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent('/books/1?x=1')
  })

  it('なければ G-3 に移す', async () => {
    mockFetch({ 'POST /api/auth/login': () => json(200, me) })
    renderComponent(<LoginPage />, { path: '/login', auth: { me: null } })
    await submitLogin(' taro_y ', 'pass1234')
    expect(await screen.findByRole('status', { name: '今の URL' })).toHaveTextContent(/^\/$/)
  })

  // TC-01-12
  it('ログインの期限が切れて移ってきたときは、M-8 をフォームの上に出す', () => {
    mockFetch({})
    renderComponent(<LoginPage />, { path: { pathname: '/login', state: { message: M[8] } }, auth: { me: null } })
    expect(screen.getByRole('alert')).toHaveTextContent(M[8])
  })
})
