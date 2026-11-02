package com.example.booklog.book;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.TextNode;

import tools.jackson.databind.JsonNode;

// Google Books API の volume（1 冊）の JSON を、本アプリで持つ形（BookData）にそろえる（FN-02 5 章の 3〜6、BR-11）
final class GoogleBooksVolumes {

	// 列の長さ（データ設計書 4.2、BR-11）
	static final int TITLE_MAX = 500;

	static final int PUBLISHER_MAX = 200;

	static final int AUTHOR_MAX = 200;

	static final int AUTHORS_MAX = 10;

	static final int THUMBNAIL_URL_MAX = 500;

	// 「2024」「2024-05」「2024-05-10」のどれか
	private static final Pattern PUBLISHED_DATE = Pattern.compile("[0-9]{4}(-[0-9]{2}(-[0-9]{2})?)?");

	private static final Pattern ISBN_13 = Pattern.compile("[0-9]{13}");

	private static final Pattern ISBN_10 = Pattern.compile("[0-9]{9}[0-9X]");

	private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

	private GoogleBooksVolumes() {
	}

	static BookData toBookData(JsonNode volume) {
		JsonNode info = volume.path("volumeInfo");
		return new BookData(volume.path("id").asString(""), isbn13(info.path("industryIdentifiers")), title(info),
				authors(info.path("authors")), truncate(text(info.path("publisher")), PUBLISHER_MAX),
				publishedDate(text(info.path("publishedDate"))), description(text(info.path("description"))),
				thumbnailUrl(text(info.path("imageLinks").path("thumbnail"))));
	}

	// title に subtitle があれば「title subtitle」（間に全角の空白）にする（5 章の 3）
	private static String title(JsonNode info) {
		String title = text(info.path("title"));
		String subtitle = text(info.path("subtitle"));
		String joined = (title == null) ? "" : title;
		if (subtitle != null) {
			joined = joined.isEmpty() ? subtitle : joined + "　" + subtitle;
		}
		String truncated = truncate(joined, TITLE_MAX);
		return (truncated == null) ? "" : truncated;
	}

	private static List<String> authors(JsonNode authors) {
		List<String> result = new ArrayList<>();
		for (JsonNode author : authors) {
			String name = truncate(text(author), AUTHOR_MAX);
			if (name != null && result.size() < AUTHORS_MAX) {
				result.add(name);
			}
		}
		return result;
	}

	// ISBN_13 を使い、ISBN_10 しかなければ 13 桁に直す。どちらもなければ持たない（5 章の 4）
	static @Nullable String isbn13(JsonNode identifiers) {
		String isbn10 = null;
		for (JsonNode identifier : identifiers) {
			String type = identifier.path("type").asString("");
			String value = identifier.path("identifier").asString("").replace("-", "");
			if ("ISBN_13".equals(type) && ISBN_13.matcher(value).matches()) {
				return value;
			}
			if ("ISBN_10".equals(type) && ISBN_10.matcher(value).matches()) {
				isbn10 = value;
			}
		}
		return (isbn10 == null) ? null : isbn10To13(isbn10);
	}

	// 先頭に 978 を付け、最後の 1 桁（チェックディジット）を計算し直す
	static String isbn10To13(String isbn10) {
		String body = "978" + isbn10.substring(0, 9);
		int sum = 0;
		for (int i = 0; i < body.length(); i++) {
			int digit = body.charAt(i) - '0';
			sum += (i % 2 == 0) ? digit : digit * 3;
		}
		return body + ((10 - sum % 10) % 10);
	}

	private static @Nullable String publishedDate(@Nullable String date) {
		return (date != null && PUBLISHED_DATE.matcher(date).matches()) ? date : null;
	}

	// HTML のタグを取り除いて文字だけにする。&amp; などは文字に戻し、改行（<br>・<p>）は残す（5 章の 6）。
	// <br> は wholeText() が改行にするので、段落の区切り（<p>）だけを足す
	static @Nullable String description(@Nullable String html) {
		if (html == null) {
			return null;
		}
		Document document = Jsoup.parseBodyFragment(html);
		document.select("p").forEach((p) -> p.after(new TextNode("\n\n")));
		String text = document.body().wholeText().replace("\r\n", "\n").replace('\r', '\n');
		text = BLANK_LINES.matcher(text).replaceAll("\n\n").strip();
		return text.isEmpty() ? null : text;
	}

	// http を https に直す（5 章の 5）。列に入らない長さの URL は持たない（表紙なしにする）
	private static @Nullable String thumbnailUrl(@Nullable String url) {
		if (url == null) {
			return null;
		}
		String https = url.startsWith("http://") ? "https://" + url.substring("http://".length()) : url;
		return (https.startsWith("https://") && https.length() <= THUMBNAIL_URL_MAX) ? https : null;
	}

	// 文字列で、トリムして空でないものだけを返す
	private static @Nullable String text(JsonNode node) {
		if (!node.isString()) {
			return null;
		}
		String value = node.asString().strip();
		return value.isEmpty() ? null : value;
	}

	// 長さ（コードポイント）を max までにする
	static @Nullable String truncate(@Nullable String value, int max) {
		if (value == null || value.codePointCount(0, value.length()) <= max) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, max)).strip();
	}

}
