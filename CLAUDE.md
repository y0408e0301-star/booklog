# Claude Code への指示

@CONTRIBUTING.md

上の開発ルールは **例外なく厳守する**。ユーザーに急ぎと言われても、変更が 1 行でも同じ。

## 作業を始める前に必ずやること

1. `git config core.hooksPath` が `.githooks` か確かめる。違えば `git config core.hooksPath .githooks` を実行する
2. 対応する Issue を確かめる（`gh issue list`）。なければ `gh issue create` で立ててから作業する
3. `git switch main` → `git pull` → `git switch -c <種類>/<Issue 番号>-<短い説明>` でブランチを切る

## してはいけないこと

- main でファイルを変更してコミットすること、main へ push すること
- `--no-verify`、`git config core.hooksPath` の変更、force push でフックやルールを回避すること
- フックに拒否されたときに回避策を探すこと。拒否されたら上の手順に戻る
- ユーザーの了承なしに PR をマージすること。PR を作ったら URL を伝えて指示を待つ
- 下の「サーバーのポート」と違うポートでサーバーを起動すること

## 文書

- 文書は Markdown で [docs/](docs/README.md) に書く。構成・版数の付け方・ID の付け方は [docs/README.md](docs/README.md) に従う（Raise timeline リポジトリと同じ形）
- 図は Mermaid 記法かテキストで書き、画像ファイルにしない
- 詳細文書を変えたら、その文書と本体（要件定義書または基本設計書）の版数を上げ、改訂履歴に書く
- 仕様が変わったら、実装と同じ PR の中で文書も直す

## サーバーのポート

動作確認などでサーバーを起動するときは、必ず次のポートを使う。

| サーバー | ポート | 決めている場所 |
| --- | --- | --- |
| データベース（PostgreSQL） | 5432 | compose.yaml |
| API（Spring Boot） | 8080 | Spring Boot の既定値（`server.port` は指定しない） |
| 画面（Vite） | 5173 | Vite の既定値（`--port` は指定しない） |

- ポートが使われていたら、そのポートを使っているプロセスを止めてから、上のポートで起動し直す
- 別のポートでの一時起動（`--port`、`server.port`、環境変数 `PORT` など）はしない
- 接続先などを差し替えた一時的な設定ファイルで起動しない

## PR を作るとき

- タイトルは変更内容を日本語で短く書く（squash マージ後のコミットメッセージになる）
- 本文はテンプレートに従い、`Closes #<Issue 番号>` を必ず書く
