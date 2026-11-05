import { useEffect, useId, useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import { useNavigate } from 'react-router'
import { ApiError, editProfile, fetchProfile, profileUrl, type Profile } from '../api'
import { useAuth } from '../auth/AuthContext'
import { isSubmitKey, remainingText, useAutoGrow } from '../components/textarea'
import { useToast } from '../components/ToastContext'
import ui from '../components/ui.module.css'
import WithdrawDialog from '../components/WithdrawDialog'
import { M } from '../messages'
import { BIO_MAX, countChars, DISPLAY_NAME_MAX, limitChars, normalizeBody } from '../validation'
import styles from './ProfilePage.module.css'
import formStyles from './ReviewFormPage.module.css'

type State =
  | { status: 'loading' }
  | { status: 'loaded', profile: Profile }
  | { status: 'failed' }

// G-9 プロフィールの編集画面（画面設計書 11 章、FN-11 PF-6〜PF-8）
export default function EditProfilePage() {
  const { me } = useAuth()
  const username = me?.username
  const [attempt, setAttempt] = useState(0)
  if (!username) return null
  return <EditProfileLoader key={attempt} username={username} onReload={() => setAttempt((n) => n + 1)} />
}

function EditProfileLoader({ username, onReload }: { username: string, onReload: () => void }) {
  const [state, setState] = useState<State>({ status: 'loading' })

  useEffect(() => {
    let active = true
    async function load() {
      try {
        const profile = await fetchProfile(username)
        if (active) setState({ status: 'loaded', profile })
      } catch {
        if (active) setState({ status: 'failed' })
      }
    }
    void load()
    return () => {
      active = false
    }
  }, [username])

  if (state.status === 'loading') return <p className={ui.empty}>読み込み中…</p>
  if (state.status === 'failed') {
    return (
      <div className={ui.empty} role="alert">
        <p>{M[29]}</p>
        <button type="button" className={ui.secondary} onClick={onReload}>再読み込み</button>
      </div>
    )
  }
  return <EditProfileForm profile={state.profile} />
}

type Errors = { displayName?: string, bio?: string }

function EditProfileForm({ profile }: { profile: Profile }) {
  const navigate = useNavigate()
  const toast = useToast()
  const { signedIn } = useAuth()
  const id = useId()
  const [displayName, setDisplayName] = useState(profile.displayName)
  const [bio, setBio] = useState(profile.bio)
  const [errors, setErrors] = useState<Errors>({})
  const [sending, setSending] = useState(false)
  const [withdrawing, setWithdrawing] = useState(false)
  // 素早く 2 回押しても 1 回だけ送る
  const sendingRef = useRef(false)
  const nameRef = useRef<HTMLInputElement>(null)
  const bioRef = useRef<HTMLTextAreaElement>(null)
  useAutoGrow(bioRef, 8)

  const newName = displayName.trim()
  const newBio = normalizeBody(bio)
  // 何も変えていないとき、送信中は押せない（PF-6）
  const canSubmit = (newName !== profile.displayName || newBio !== profile.bio) && !sending

  // 「←」・「キャンセル」：変えずに G-8 に戻る
  function back() {
    navigate(profileUrl(profile.username))
  }

  async function submit() {
    if (!canSubmit || sendingRef.current) return
    if (newName === '') {
      setErrors({ displayName: M[5] })
      nameRef.current?.focus()
      return
    }
    setErrors({})
    sendingRef.current = true
    setSending(true)
    try {
      const saved = await editProfile(newName, newBio)
      // ヘッダーの頭文字の丸も新しい表示名にする
      signedIn({ username: saved.username, displayName: saved.displayName })
      // G-9 には戻らないよう、履歴を置き換える
      navigate(profileUrl(saved.username), { replace: true })
    } catch (err) {
      sendingRef.current = false
      setSending(false)
      if (err instanceof ApiError && (err.field === 'displayName' || err.field === 'bio')) {
        setErrors({ [err.field]: err.message })
      } else {
        // 入力した内容は残す
        toast(M[30])
      }
    }
  }

  function handleSubmit(e: FormEvent) {
    e.preventDefault()
    void submit()
  }

  // 自己紹介の入力欄では Enter キーで改行し、Ctrl＋Enter で送る（画面設計書 2.12）
  function handleKeyDown(e: KeyboardEvent) {
    if (isSubmitKey(e)) {
      e.preventDefault()
      void submit()
    }
  }

  return (
    <>
      <div className={formStyles.head}>
        <button type="button" className={formStyles.back} aria-label="プロフィールに戻る" onClick={back}>←</button>
        <h1>プロフィールを編集</h1>
      </div>
      <form className={formStyles.form} onSubmit={handleSubmit} noValidate>
        <div className={formStyles.field}>
          <label htmlFor={`${id}-name`} className={formStyles.label}>表示名</label>
          {/* 上限を超えて入力はできない（PF-7） */}
          <input id={`${id}-name`} ref={nameRef} type="text" value={displayName}
            className={errors.displayName ? `${styles.nameInput} ${formStyles.invalid}` : styles.nameInput}
            aria-invalid={errors.displayName ? true : undefined}
            aria-describedby={errors.displayName ? `${id}-name-error` : undefined}
            onChange={(e) => {
              setDisplayName(limitChars(e.target.value, DISPLAY_NAME_MAX))
              setErrors((prev) => ({ ...prev, displayName: undefined }))
            }} />
          {errors.displayName && <p className={formStyles.error} id={`${id}-name-error`}>{errors.displayName}</p>}
        </div>

        <div className={formStyles.field}>
          <label htmlFor={`${id}-bio`} className={formStyles.label}>自己紹介</label>
          <textarea id={`${id}-bio`} ref={bioRef} value={bio} onKeyDown={handleKeyDown}
            className={`${formStyles.body} ${styles.bioInput}${errors.bio ? ` ${formStyles.invalid}` : ''}`}
            aria-invalid={errors.bio ? true : undefined}
            aria-describedby={`${id}-count${errors.bio ? ` ${id}-bio-error` : ''}`}
            onChange={(e) => {
              setBio(limitChars(e.target.value, BIO_MAX))
              setErrors((prev) => ({ ...prev, bio: undefined }))
            }} />
          <div className={formStyles.bodyFoot}>
            {errors.bio && <p className={formStyles.error} id={`${id}-bio-error`}>{errors.bio}</p>}
            <span id={`${id}-count`} className={formStyles.count}>{remainingText(BIO_MAX - countChars(newBio))}</span>
          </div>
        </div>

        <div className={formStyles.buttons}>
          <button type="button" className={ui.secondary} onClick={back} disabled={sending}>キャンセル</button>
          <button type="submit" className={ui.primary} disabled={!canSubmit}>{sending ? '保存中…' : '保存する'}</button>
        </div>
      </form>
      {/* いちばん下に「退会する」（PF-8）。押すと G-13（A-10） */}
      <section className={styles.withdraw} aria-labelledby={`${id}-withdraw`}>
        <h2 id={`${id}-withdraw`}>退会</h2>
        <p>退会すると、あなたのレビュー・写真・コメント・本棚などがすべて削除され、元に戻せません。</p>
        <button type="button" className={ui.danger} onClick={() => setWithdrawing(true)}>退会する</button>
      </section>
      {withdrawing && <WithdrawDialog onCancel={() => setWithdrawing(false)} />}
    </>
  )
}
