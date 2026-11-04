package com.example.booklog.common;

import org.springframework.http.HttpStatus;

// API が返すエラーの code と、画面にそのまま出すメッセージ（機能定義書 3.3・4 章）。
// 機能を作るたびに、その機能定義書 9 章の code を足す
public enum ErrorCode {

	// ユーザー登録・ログイン（FN-01 9 章。M-1〜M-8）
	USERNAME_INVALID(HttpStatus.BAD_REQUEST, "ユーザー ID は 3〜15 文字の半角英数字と「_」で入力してください。"),

	USERNAME_TAKEN(HttpStatus.CONFLICT, "このユーザー ID は使われています。"),

	PASSWORD_INVALID(HttpStatus.BAD_REQUEST, "パスワードは英字と数字を含む 8〜64 文字の半角文字で入力してください。"),

	DISPLAY_NAME_REQUIRED(HttpStatus.BAD_REQUEST, "表示名を入力してください。"),

	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "ユーザー ID かパスワードが違います。"),

	LOGIN_LOCKED(HttpStatus.LOCKED, "ログインに続けて失敗したため、15 分間ログインできません。"),

	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "ログインの有効期限が切れました。もう一度ログインしてください。"),

	// 画面には出さない（画面が POST /api/auth/refresh で取り直して、やり直す）
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "ログインの有効期限が切れました。もう一度ログインしてください。"),

	REFRESH_FAILED(HttpStatus.UNAUTHORIZED, "ログインの有効期限が切れました。もう一度ログインしてください。"),

	// 書籍検索・登録（FN-02 9 章。M-12・M-14・M-15）
	QUERY_REQUIRED(HttpStatus.BAD_REQUEST, "ISBN か書名を入力してください。"),

	// Google Books API が止まっている・5 秒を超えた・その日の上限に達した（B-8）
	BOOK_SEARCH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "本の検索がいまできません。時間をおいてもう一度お試しください。"),

	// 選んだ本が Google Books に見つからない（消された）
	GOOGLE_BOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "この本は見つかりません。"),

	// 本のページ（FN-10 9 章。M-15）
	BOOK_NOT_FOUND(HttpStatus.NOT_FOUND, "この本は見つかりません。"),

	// レビュー（FN-03 9 章。M-16〜M-18・M-20）
	RATING_REQUIRED(HttpStatus.BAD_REQUEST, "星を 1〜5 から選んでください。"),

	BODY_REQUIRED(HttpStatus.BAD_REQUEST, "感想を入力してください。"),

	// 同じ本のレビューがすでにある（別のブラウザで先に書いたなど）
	REVIEW_EXISTS(HttpStatus.CONFLICT, "この本のレビューはすでに書いています。"),

	REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "このレビューは削除されました。"),

	// 写真（FN-04 9 章。M-21〜M-23）
	TOO_MANY_PHOTOS(HttpStatus.BAD_REQUEST, "写真は 1 つのレビューに 4 枚までです。"),

	PHOTO_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "添えられるのは 5MB までの JPEG・PNG・WebP の画像です。"),

	PHOTO_TYPE_INVALID(HttpStatus.BAD_REQUEST, "添えられるのは 5MB までの JPEG・PNG・WebP の画像です。"),

	// S3 への保存に失敗した。入力した中身は画面に残す
	PHOTO_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "投稿に失敗しました。時間をおいてもう一度お試しください。"),

	// 画面を通さずに送られた、条件に合わない入力（JSON が読めない、上限を超えた文字数など）
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "入力が正しくありません。"),

	// 他人のものを変えようとした、CSRF トークンが合わないとき。画面には M-30 を出す
	FORBIDDEN(HttpStatus.FORBIDDEN, "操作に失敗しました。時間をおいてもう一度お試しください。"),

	NOT_FOUND(HttpStatus.NOT_FOUND, "見つかりません。"),

	// 思いがけない失敗（M-30）
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "操作に失敗しました。時間をおいてもう一度お試しください。");

	private final HttpStatus status;

	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String message() {
		return this.message;
	}

}
