import ui from '../components/ui.module.css'

// G-3 タイムライン画面（画面設計書 5 章）の仮の画面。
// レビューがないと中身を確かめられないので、タブと一覧はタイムライン（開発計画書 1-5）で作る
export default function HomePage() {
  return <p className={ui.empty}>まだレビューがありません。本を探して、最初のレビューを書いてみましょう。</p>
}
