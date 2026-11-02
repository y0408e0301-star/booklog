package com.example.booklog.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.booklog.RepositoryTest;

import tools.jackson.databind.json.JsonMapper;

// 本（books）と、Google Books API の検索の結果（book_search_results）・呼び出し回数（google_books_usage）の SQL
@RepositoryTest
class BookRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

	@Autowired
	BookRepository books;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	private BookSearchCache cache;

	private GoogleBooksUsage usage;

	@BeforeEach
	void setUp() {
		this.cache = new BookSearchCache(this.jdbc, JsonMapper.builder().build());
		this.usage = new GoogleBooksUsage(this.jdbc);
	}

	@Test
	void 著者の配列を順番どおりに保存して読む() {
		long id = this.books.saveAndFlush(new Book(data("a", List.of("著者 B", "著者 A")), NOW)).getId();
		this.em.clear();

		Book book = this.books.findById(id).orElseThrow();
		assertThat(book.getAuthors()).containsExactly("著者 B", "著者 A");
		assertThat(this.books.findByGoogleBooksId("a")).get().extracting(Book::getId).isEqualTo(id);
		assertThat(this.books.findByGoogleBooksId("A")).isEmpty();
	}

	@Test
	void 著者がいない本は空の配列() {
		long id = this.books.saveAndFlush(new Book(data("a", List.of()), NOW)).getId();
		this.em.clear();
		assertThat(this.books.findById(id).orElseThrow().getAuthors()).isEmpty();
	}

	// TC-02-7：同じ Google Books の ID は 1 つ（BR-11）
	@Test
	void 同じGoogleBooksのIDは保存できない() {
		this.books.saveAndFlush(new Book(data("a", List.of()), NOW));
		assertThatThrownBy(() -> this.books.saveAndFlush(new Book(data("a", List.of()), NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- 検索の結果（BR-12）----

	@Test
	void 検索の結果を保存してsinceより後のものだけを読む() {
		BookSearchPage page = new BookSearchPage(
				List.of(new BookSearchItem("a", "こころ", List.of("夏目漱石"), null, "2003", null, "https://x/a")), true);
		this.cache.save("q:夏目漱石", 1, page, NOW);

		assertThat(this.cache.find("q:夏目漱石", 1, NOW.minusSeconds(1))).contains(page);
		assertThat(this.cache.find("q:夏目漱石", 1, NOW)).isEmpty();
		assertThat(this.cache.find("q:夏目漱石", 2, NOW.minusSeconds(1))).isEmpty();
	}

	@Test
	void 同じ問い合わせとページは置き換える() {
		this.cache.save("isbn:9784101010014", 1, new BookSearchPage(List.of(), false), NOW);
		BookSearchPage newer = new BookSearchPage(List.of(), true);
		this.cache.save("isbn:9784101010014", 1, newer, NOW.plusSeconds(60));

		assertThat(this.cache.find("isbn:9784101010014", 1, NOW)).contains(newer);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM book_search_results", Integer.class)).isEqualTo(1);
	}

	@Test
	void 古い検索の結果を消す() {
		this.cache.save("q:a", 1, new BookSearchPage(List.of(), false), NOW.minusSeconds(100));
		this.cache.save("q:b", 1, new BookSearchPage(List.of(), false), NOW);

		assertThat(this.cache.deleteFetchedBefore(NOW)).isEqualTo(1);
		assertThat(this.cache.find("q:b", 1, NOW.minusSeconds(1))).isPresent();
	}

	// ---- 呼び出し回数（BR-13、データ設計書 8.3）----

	@Test
	void 呼び出し回数は日ごとに1から数える() {
		assertThat(this.usage.increment(TODAY)).hasValue(1);
		assertThat(this.usage.increment(TODAY)).hasValue(2);
		assertThat(this.usage.increment(TODAY.plusDays(1))).hasValue(1);
	}

	// TC-02-5
	@Test
	void 千回に達したら増やさずに空を返す() {
		this.jdbc.update("INSERT INTO google_books_usage (usage_date, call_count) VALUES (?, 999)", TODAY);
		assertThat(this.usage.increment(TODAY)).hasValue(1000);
		assertThat(this.usage.increment(TODAY)).isEmpty();
		assertThat(this.jdbc.queryForObject("SELECT call_count FROM google_books_usage WHERE usage_date = ?",
				Integer.class, TODAY))
			.isEqualTo(1000);
	}

	@Test
	void 古い日の回数を消す() {
		this.usage.increment(TODAY.minusDays(91));
		this.usage.increment(TODAY.minusDays(90));
		assertThat(this.usage.deleteBefore(TODAY.minusDays(90))).isEqualTo(1);
	}

	private static BookData data(String googleBooksId, List<String> authors) {
		return new BookData(googleBooksId, null, "書名", authors, null, null, null, null);
	}

}
