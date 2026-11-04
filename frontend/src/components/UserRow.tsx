import { Link } from 'react-router'
import { profileUrl, type UserSummary } from '../api'
import { countChars } from '../validation'
import Avatar from './Avatar'
import FollowButton from './FollowButton'
import styles from './UserRow.module.css'

// 自己紹介の 1 行目は 60 文字を超えたら「…」で省く（画面設計書 2.11、FN-11 5 章の 4）
const BIO_LINE_MAX = 60

function bioLine(text: string): string {
  return countChars(text) > BIO_LINE_MAX ? `${Array.from(text).slice(0, BIO_LINE_MAX).join('')}…` : text
}

// ユーザーの行（画面設計書 2.11。G-10 で使う）。名前を押すとその人の G-8（F-6）。自分の行にはフォローボタンを出さない
export default function UserRow({ user }: { user: UserSummary }) {
  return (
    <li className={styles.row} aria-label={user.displayName}>
      <Link to={profileUrl(user.username)} className={styles.avatar} tabIndex={-1} aria-hidden="true">
        <Avatar displayName={user.displayName} size={40} />
      </Link>
      <div className={styles.main}>
        <Link to={profileUrl(user.username)} className={styles.names}>
          <span className={styles.name}>{user.displayName}</span>
          <span className={styles.username}>@{user.username}</span>
        </Link>
        {user.bioFirstLine && <p className={styles.bio}>{bioLine(user.bioFirstLine)}</p>}
      </div>
      {!user.me && <FollowButton username={user.username} following={user.followedByMe} />}
    </li>
  )
}
