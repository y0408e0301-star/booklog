import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError, isUsernameAvailable, signup } from '../api'
import { useAuth } from '../auth/AuthContext'
import { M } from '../messages'
import {
  DISPLAY_NAME_MAX, PASSWORD_MAX, USERNAME_MAX, isValidDisplayName, isValidPassword, isValidUsername, limitChars,
} from '../validation'
import styles from './AuthPage.module.css'

type Key = 'username' | 'displayName' | 'password' | 'passwordConfirm'

type Errors = Partial<Record<Key, string>>

const KEYS: Key[] = ['username', 'displayName', 'password', 'passwordConfirm']

// 入力の確認（FN-01 4 章）。条件に合わない項目にメッセージを付けて返す
function validate(values: Record<Key, string>): Errors {
  const errors: Errors = {}
  if (!isValidUsername(values.username.trim())) errors.username = M[2]
  if (!isValidDisplayName(values.displayName.trim())) errors.displayName = M[5]
  if (!isValidPassword(values.password)) errors.password = M[3]
  if (values.passwordConfirm !== values.password) errors.passwordConfirm = M[4]
  return errors
}

// G-2 ユーザー登録画面（A-1〜A-4、画面設計書 4 章）
export default function SignupPage() {
  const navigate = useNavigate()
  const { signedIn } = useAuth()
  const [values, setValues] = useState<Record<Key, string>>(
    { username: '', displayName: '', password: '', passwordConfirm: '' })
  const [errors, setErrors] = useState<Errors>({})
  const [message, setMessage] = useState<string | null>(null)
  const [sending, setSending] = useState(false)
  const inputs = useRef<Partial<Record<Key, HTMLInputElement | null>>>({})

  // 入力したら、その入力欄のメッセージを消す（画面設計書 2.4）
  // 表示名は 31 文字目（コードポイント）を入力できない（A-4）
  function change(key: Key, value: string) {
    if (key === 'displayName') value = limitChars(value, DISPLAY_NAME_MAX)
    setValues((v) => ({ ...v, [key]: value }))
    setErrors((e) => ({ ...e, [key]: undefined }))
  }

  // ユーザー ID の入力欄から離れたときに、使われていないかを確かめる（画面設計書 4 章）
  async function checkUsername() {
    const username = values.username.trim()
    if (!isValidUsername(username)) return
    try {
      if (!(await isUsernameAvailable(username))) setErrors((e) => ({ ...e, username: M[1] }))
    } catch {
      // 確かめられなくても、「登録する」を押したときに API が確かめる
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setMessage(null)
    const found = validate(values)
    setErrors(found)
    const first = KEYS.find((k) => found[k])
    if (first) {
      inputs.current[first]?.focus()
      return
    }
    setSending(true)
    try {
      const me = await signup(values.username.trim(), values.displayName.trim(), values.password)
      signedIn(me)
      navigate('/', { replace: true })
    } catch (err) {
      setSending(false)
      // field があればその入力欄の下に、なければフォームの上に出す
      const field = err instanceof ApiError ? KEYS.find((k) => k === err.field) : undefined
      if (field) {
        setErrors({ [field]: (err as ApiError).message })
        inputs.current[field]?.focus()
      } else {
        setMessage(err instanceof ApiError ? err.message : M[30])
      }
    }
  }

  function renderField(key: Key, label: string, type: string, autoComplete: string, maxLength?: number, hint?: string) {
    const id = `signup-${key}`
    const error = errors[key]
    return (
      <div className={`${styles.field} ${error ? styles.invalid : ''}`}>
        <label htmlFor={id}>{label}</label>
        <input id={id} type={type} autoComplete={autoComplete} maxLength={maxLength}
          ref={(el) => { inputs.current[key] = el }}
          autoFocus={key === 'username'}
          value={values[key]} onChange={(e) => change(key, e.target.value)}
          onBlur={key === 'username' ? checkUsername : undefined}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined} />
        {hint && <p className={styles.hint} id={`${id}-hint`}>{hint}</p>}
        {error && <p className={styles.error} id={`${id}-error`}>{error}</p>}
      </div>
    )
  }

  return (
    <main className={styles.page}>
      <h1 className={styles.subtitle}>アカウントを作る</h1>
      <form onSubmit={handleSubmit} noValidate>
        {renderField('username', 'ユーザー ID', 'text', 'username', USERNAME_MAX, '3〜15 文字の半角英数字と「_」')}
        {renderField('displayName', '表示名', 'text', 'nickname')}
        {renderField('password', 'パスワード', 'password', 'new-password', PASSWORD_MAX, '英字と数字を含む 8〜64 文字の半角文字')}
        {renderField('passwordConfirm', 'パスワード（確認）', 'password', 'new-password', PASSWORD_MAX)}
        {message && <div className={styles.message} role="alert">{message}</div>}
        <button type="submit" className={styles.primary} disabled={sending}>
          {sending ? '登録中…' : '登録する'}
        </button>
      </form>
      <p className={styles.link}><Link to="/login">ログインはこちら</Link></p>
    </main>
  )
}
