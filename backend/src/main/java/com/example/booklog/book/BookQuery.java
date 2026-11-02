package com.example.booklog.book;

import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 書籍検索の文字を、ISBN か書名・著者名かに分けたもの（FN-02 B-2）。
// cacheKey：検索の結果を使い回すときのキー（BR-12。book_search_results.query）
// googleQuery：Google Books API に送る q（FN-02 5 章の 1）
// isbn：ISBN として探すか。書名・著者名のときだけ printType=books を付ける
record BookQuery(String cacheKey, String googleQuery, boolean isbn) {

	static final int MAX_LENGTH = 100;

	// 「-」と空白（全角を含む）を取り除いて、10 桁（最後は X でもよい）か 13 桁の数字なら ISBN
	private static final Pattern ISBN_SEPARATORS = Pattern.compile("[-\\s\\u3000]");

	private static final Pattern ISBN = Pattern.compile("[0-9]{9}[0-9X]|[0-9]{13}");

	private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");

	static BookQuery parse(@Nullable String raw) {
		String text = (raw == null) ? "" : raw.strip();
		if (text.isEmpty()) {
			throw new ApiException(ErrorCode.QUERY_REQUIRED, "q");
		}
		if (text.codePointCount(0, text.length()) > MAX_LENGTH) {
			// 画面では 101 文字目を入力できない
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		String digits = ISBN_SEPARATORS.matcher(text).replaceAll("").toUpperCase(Locale.ROOT);
		if (ISBN.matcher(digits).matches()) {
			return new BookQuery("isbn:" + digits, "isbn:" + digits, true);
		}
		// 英字の大文字・小文字と空白の数が違うだけの検索は、同じ結果を使い回す。Google Books API には入れたまま送る
		String key = "q:" + SPACES.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ");
		return new BookQuery(key, text, false);
	}

}
