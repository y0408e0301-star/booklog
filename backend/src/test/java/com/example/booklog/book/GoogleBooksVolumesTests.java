package com.example.booklog.book;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Google Books API の 1 冊を、本アプリで持つ形にそろえる（FN-02 5 章の 3〜6、BR-11）。TC-02-8・TC-02-9
class GoogleBooksVolumesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void すべての項目がある本() {
		BookData book = toBookData(
				"""
						{"id":"zyTCAlFPjgYC","volumeInfo":{
						  "title":"吾輩は猫である","authors":["夏目漱石"],"publisher":"新潮社","publishedDate":"2003-06",
						  "description":"猫の目から見た人間の話。",
						  "industryIdentifiers":[{"type":"ISBN_10","identifier":"4101010013"},{"type":"ISBN_13","identifier":"9784101010014"}],
						  "imageLinks":{"thumbnail":"https://books.google.com/books/content?id=zyTCAlFPjgYC&zoom=1"}}}
						""");
		assertThat(book).isEqualTo(new BookData("zyTCAlFPjgYC", "9784101010014", "吾輩は猫である", List.of("夏目漱石"), "新潮社",
				"2003-06", "猫の目から見た人間の話。", "https://books.google.com/books/content?id=zyTCAlFPjgYC&zoom=1"));
	}

	// TC-02-8
	@Test
	void 表紙もISBNもない本はその項目を持たない() {
		BookData book = toBookData("""
				{"id":"abc","volumeInfo":{"title":"こころ"}}
				""");
		assertThat(book).isEqualTo(new BookData("abc", null, "こころ", List.of(), null, null, null, null));
	}

	// TC-02-8：5 章の 4
	@Test
	void ISBN10だけの本は13桁に直す() {
		assertThat(isbn("""
				[{"type":"OTHER","identifier":"UOM:39015"},{"type":"ISBN_10","identifier":"4-10-101001-3"}]
				""")).isEqualTo("9784101010014");
		// チェックディジットが X の ISBN-10
		assertThat(isbn("""
				[{"type":"ISBN_10","identifier":"408872094X"}]
				""")).isEqualTo("9784088720944");
		assertThat(isbn("[{\"type\":\"ISSN\",\"identifier\":\"12345678\"}]")).isNull();
	}

	@Test
	void ISBN10から13桁への直し方() {
		assertThat(GoogleBooksVolumes.isbn10To13("4101010013")).isEqualTo("9784101010014");
		assertThat(GoogleBooksVolumes.isbn10To13("0306406152")).isEqualTo("9780306406157");
		// 計算した最後の桁が 10 になるときは 0
		assertThat(GoogleBooksVolumes.isbn10To13("0000000040")).isEqualTo("9780000000040");
	}

	// TC-02-8：5 章の 3
	@Test
	void subtitleがあれば全角の空白でつなぐ() {
		assertThat(toBookData("""
				{"id":"a","volumeInfo":{"title":"深夜特急","subtitle":"香港・マカオ"}}
				""").title()).isEqualTo("深夜特急　香港・マカオ");
		assertThat(toBookData("""
				{"id":"a","volumeInfo":{"subtitle":"副題だけ"}}
				""").title()).isEqualTo("副題だけ");
	}

	// TC-02-8：5 章の 5
	@Test
	void 表紙のURLはhttpsに直す() {
		assertThat(toBookData(
				"""
						{"id":"a","volumeInfo":{"title":"t","imageLinks":{"thumbnail":"http://books.google.com/x?id=a&edge=curl"}}}
						""")
			.thumbnailUrl()).isEqualTo("https://books.google.com/x?id=a&edge=curl");
	}

	// TC-02-8：5 章の 6
	@Test
	void 紹介文はHTMLのタグを取り除き改行と文字の参照を戻す() {
		assertThat(GoogleBooksVolumes.description("<p>一行目<br>二行目</p><p><b>太字</b> &amp; 記号 &lt;b&gt;</p>"))
			.isEqualTo("一行目\n二行目\n\n太字 & 記号 <b>");
		assertThat(GoogleBooksVolumes.description("<p> </p>")).isNull();
	}

	// TC-02-9：本の情報の <script> は、文字のまま持つ（画面が文字として出す。N-17）
	@Test
	void 書名のscriptは文字のまま持ち紹介文のタグは取り除く() {
		BookData book = toBookData(
				"""
						{"id":"a","volumeInfo":{"title":"<script>alert(1)</script>","description":"<script>alert(1)</script>説明"}}
						""");
		assertThat(book.title()).isEqualTo("<script>alert(1)</script>");
		assertThat(book.description()).doesNotContain("<script>").endsWith("説明");
	}

	@Test
	void 出版日は年か年月か年月日の形だけを持つ() {
		assertThat(publishedDate("2024")).isEqualTo("2024");
		assertThat(publishedDate("2024-05-10")).isEqualTo("2024-05-10");
		assertThat(publishedDate("2024-05-10T00:00:00Z")).isNull();
		assertThat(publishedDate("2024年")).isNull();
	}

	// BR-11
	@Test
	void 列に入らない長さは切る() {
		String longName = "あ".repeat(201);
		String authors = "[" + "\"" + longName + "\"," + "\"b\",".repeat(10) + "\"\"]";
		BookData book = toBookData("{\"id\":\"a\",\"volumeInfo\":{\"title\":\"" + "😀".repeat(501)
				+ "\",\"publisher\":\"" + "い".repeat(201) + "\",\"authors\":" + authors + "}}");
		assertThat(book.title().codePointCount(0, book.title().length())).isEqualTo(500);
		assertThat(book.publisher()).hasSize(200);
		assertThat(book.authors()).hasSize(10);
		assertThat(book.authors().get(0)).hasSize(200);
	}

	private static BookData toBookData(String json) {
		return GoogleBooksVolumes.toBookData(JSON.readTree(json));
	}

	private static String isbn(String identifiers) {
		JsonNode node = JSON.readTree(identifiers);
		return GoogleBooksVolumes.isbn13(node);
	}

	private static String publishedDate(String date) {
		return toBookData("{\"id\":\"a\",\"volumeInfo\":{\"title\":\"t\",\"publishedDate\":\"" + date + "\"}}")
			.publishedDate();
	}

}
