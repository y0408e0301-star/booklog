import { useRef, useState } from 'react'
import { refreshPhotoUrls, type ReviewPhoto } from '../api'
import PhotoDialog from './PhotoDialog'
import styles from './ReviewPhotos.module.css'

// レビューのカードの写真（画面設計書 2.7、FN-04 PH-6〜PH-8）。
// 1 枚：横幅いっぱい、2 枚：横に 2 つ、3 枚：左に 1 つ・右に 2 つ、4 枚：2 行 2 列。押すと G-11
export default function ReviewPhotos({ photos: initial }: { photos: ReviewPhoto[] }) {
  // 取り直した URL（refreshed は取り直し済みか）。レビューが変わったら（タイムラインの読み直しなど）初めの URL に戻す
  const [state, setState] = useState({ initial, photos: initial, refreshed: false, failed: false })
  const [open, setOpen] = useState<number | null>(null)
  const current = state.initial === initial ? state : { initial, photos: initial, refreshed: false, failed: false }
  const { photos, refreshed, failed } = current

  // 取り直し中のもの。期限が切れると写真の数だけ onError が同時に起きるので、送るのは 1 回にしてその結果を待つ
  const refreshing = useRef<{ initial: ReviewPhoto[], promise: Promise<void> } | null>(null)

  // 15 分の期限が切れた・読み込めなかったときは、新しい URL を取り直す（PH-8、FN-04 3.2）。
  // 結果に入っていない写真は削除されたもの（FN-04 6 章）なので、取り直せなかったとみなす
  function refresh(): Promise<void> {
    if (failed) return Promise.resolve()
    if (refreshing.current?.initial === initial) return refreshing.current.promise
    const promise = (async () => {
      try {
        const fresh = await refreshPhotoUrls(photos.map((i) => i.id))
        const byId = new Map(fresh.map((i) => [i.id, i]))
        const next = photos.map((i) => byId.get(i.id))
        if (next.every((i) => i !== undefined)) {
          setState({ initial, photos: next, refreshed: true, failed: false })
        } else {
          setState({ initial, photos, refreshed, failed: true })
        }
      } catch {
        setState({ initial, photos, refreshed, failed: true })
      }
    })().finally(() => {
      if (refreshing.current?.promise === promise) refreshing.current = null
    })
    refreshing.current = { initial, promise }
    return promise
  }

  const expired = () => photos.some((i) => Date.parse(i.expiresAt) <= Date.now())

  // 読み込めなかったとき。取り直した URL がまだ期限内なのに読み込めなければ、取り直しても直らないので
  // 「写真を読み込めませんでした」を出す（FN-04 3.2）。期限が切れていれば、また取り直す
  function handleError() {
    if (failed) return
    if (refreshed && !expired()) {
      setState({ initial, photos, refreshed, failed: true })
      return
    }
    void refresh()
  }

  // G-11 を開く前に、期限が切れていれば取り直す
  async function openAt(index: number) {
    if (expired()) await refresh()
    setOpen(index)
  }

  if (photos.length === 0) return null
  if (failed) return <p className={styles.failed}>写真を読み込めませんでした</p>

  return (
    <>
      <div className={`${styles.grid} ${styles[`count${photos.length}`]}`}>
        {photos.map((photo, index) => (
          <button key={photo.id} type="button" className={styles.cell} onClick={() => void openAt(index)}
            aria-label={`写真 ${index + 1} / ${photos.length} を拡大`}>
            {/* 期限切れなどで読み込めなかったら URL を取り直す */}
            <img src={photo.url} alt="" loading="lazy" onError={handleError} />
          </button>
        ))}
      </div>
      {open !== null && (
        <PhotoDialog photos={photos} index={open} onIndexChange={setOpen} onClose={() => setOpen(null)}
          onError={handleError} />
      )}
    </>
  )
}
