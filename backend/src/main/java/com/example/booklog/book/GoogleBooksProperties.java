package com.example.booklog.book;

import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

// application.properties の app.google-books.*
// baseUrl：Google Books API の接続先。E2E（2-1）では偽物のサーバーに向ける
// apiKey：API キー（N-18）。開発中は環境変数 GOOGLE_BOOKS_API_KEY、公開先は Parameter Store から渡す。
// 空なら key= を付けずに呼ぶ（Google の共有の枠になり、その日の上限で 429 が返ることが多い。README）
// timeout：応答をこの時間で打ち切り、使えないものとする（B-8）
@ConfigurationProperties("app.google-books")
record GoogleBooksProperties(String baseUrl, @Nullable String apiKey, Duration timeout) {

	boolean hasApiKey() {
		return this.apiKey != null && !this.apiKey.isBlank();
	}

}
