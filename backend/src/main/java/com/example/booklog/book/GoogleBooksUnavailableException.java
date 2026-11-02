package com.example.booklog.book;

// Google Books API が使えなかった（B-8）。status は Google Books API が返した HTTP ステータスで、
// つながらない・時間切れのときは 0。ログに出すため持つ（基本設計書 2.3）
class GoogleBooksUnavailableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	GoogleBooksUnavailableException(int status, String reason) {
		super("Google Books API が使えません（" + status + " " + reason + "）");
		this.status = status;
	}

	int status() {
		return this.status;
	}

}
