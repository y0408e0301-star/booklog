package com.example.booklog.book;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.booklog.common.ErrorCode;

// 書籍検索の文字を ISBN か書名・著者名かに分ける（B-2）と、使い回しのキー（BR-12）
class BookQueryTests {

	// TC-02-1
	// B-2
	@ParameterizedTest
	@ValueSource(strings = { "978-4-10-101001-4", "9784101010014", " 978 4101010014 ", "978　4101010014" })
	void 区切りを取り除いて13桁の数字ならISBNとして探す(String raw) {
		assertThat(BookQuery.parse(raw)).isEqualTo(new BookQuery("isbn:9784101010014", "isbn:9784101010014", true));
	}

	// TC-02-1：最後の 1 桁（チェックディジット）が合っているかは確かめない（FN-02 4 章）
	@Test
	void チェックディジットが合わないISBNもISBNとして探す() {
		assertThat(BookQuery.parse("978-4-10-101001-6").googleQuery()).isEqualTo("isbn:9784101010016");
	}

	// TC-02-1
	// B-2
	@Test
	void 区切りを取り除いて10桁ならISBNとして探し最後のxは大文字にする() {
		assertThat(BookQuery.parse("4101010013").googleQuery()).isEqualTo("isbn:4101010013");
		assertThat(BookQuery.parse("4-08-872094-x"))
			.isEqualTo(new BookQuery("isbn:408872094X", "isbn:408872094X", true));
	}

	// TC-02-1
	// B-2
	@ParameterizedTest
	@ValueSource(strings = { "吾輩は猫", "夏目漱石", "12345", "97841010100145", "410101001Y", "X101010013" })
	void それ以外は書名か著者名として入れたまま探す(String raw) {
		BookQuery query = BookQuery.parse(raw);
		assertThat(query.isbn()).isFalse();
		assertThat(query.googleQuery()).isEqualTo(raw);
	}

	@Test
	void 書名の使い回しのキーは英字を小文字にし続く空白を1つにまとめる() {
		BookQuery query = BookQuery.parse("  Harry 　 POTTER ");
		assertThat(query.cacheKey()).isEqualTo("q:harry potter");
		// Google Books API には、トリムしただけの文字を送る
		assertThat(query.googleQuery()).isEqualTo("Harry 　 POTTER");
		assertThat(BookQuery.parse("harry potter").cacheKey()).isEqualTo(query.cacheKey());
	}

	// TC-02-2
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "　", "\t\n" })
	void トリムして空ならM12(String raw) {
		assertApiError(() -> BookQuery.parse(raw), ErrorCode.QUERY_REQUIRED, "q");
	}

	@Test
	void 文字数は100文字までで絵文字も1文字と数える() {
		assertThat(BookQuery.parse("あ".repeat(100)).googleQuery()).hasSize(100);
		assertThat(BookQuery.parse("😀".repeat(100)).isbn()).isFalse();
		assertApiError(() -> BookQuery.parse("あ".repeat(101)), ErrorCode.BAD_REQUEST);
	}

}
