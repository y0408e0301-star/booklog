package com.example.booklog.shelf;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.booklog.RepositoryTest;
import com.example.booklog.book.Book;
import com.example.booklog.book.BookData;
import com.example.booklog.book.BookSummary;
import com.example.booklog.user.User;

// 読書ステータス（reading_statuses）の付け外しと、本棚の一覧の SQL（FN-08 5 章）
@RepositoryTest
class ReadingStatusesRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	private ReadingStatuses statuses;

	private User taro;

	private User hanako;

	private Book neko;

	private Book kokoro;

	@BeforeEach
	void setUp() {
		this.statuses = new ReadingStatuses(this.jdbc);
		this.taro = this.em.persist(new User("taro_y", "たろう", "hash", NOW));
		this.hanako = this.em.persist(new User("hanako", "はなこ", "hash", NOW));
		this.neko = this.em.persist(book("a", "吾輩は猫である", List.of("夏目漱石")));
		this.kokoro = this.em.persist(book("b", "こころ", List.of()));
	}

	// ST-2・5 章の 1：本棚の行は 1 冊に 1 つのまま、ステータスが変わる
	@Test
	void 付けて変えても行は一つ() {
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.WANT);
		assertThat(this.statuses.statusOf(this.taro.getId(), this.neko.getId())).isEqualTo(ReadingStatus.WANT);

		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READING);
		assertThat(this.statuses.statusOf(this.taro.getId(), this.neko.getId())).isEqualTo(ReadingStatus.READING);
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM reading_statuses", Long.class)).isOne();
	}

	// TC-08-3（5 章の 3）
	@Test
	void 同じステータスなら日時を変えず違えば変える() {
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		Instant old = Instant.parse("2026-01-01T00:00:00Z");
		this.jdbc.update("UPDATE reading_statuses SET updated_at = ?", Timestamp.from(old));

		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		assertThat(updatedAt()).isEqualTo(old);

		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.WANT);
		assertThat(updatedAt()).isAfter(old);
	}

	// ST-3：外す。なくても何もしない。ほかの人の本棚は変えない
	@Test
	void 外すとその人の行だけがなくなる() {
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		this.statuses.set(this.hanako.getId(), this.neko.getId(), ReadingStatus.READ);

		this.statuses.remove(this.taro.getId(), this.neko.getId());
		this.statuses.remove(this.taro.getId(), this.neko.getId());

		assertThat(this.statuses.statusOf(this.taro.getId(), this.neko.getId())).isNull();
		assertThat(this.statuses.statusOf(this.hanako.getId(), this.neko.getId())).isEqualTo(ReadingStatus.READ);
	}

	// ST-5・5 章の 5：日時の新しい順、同じ日時なら本の ID の大きい順。続きは最後の行より後ろ
	@Test
	void 本棚は新しい順で同じ日時なら本のIDの大きい順() {
		Book third = this.em.persist(book("c", "三四郎", List.of()));
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		this.statuses.set(this.taro.getId(), this.kokoro.getId(), ReadingStatus.READ);
		this.statuses.set(this.taro.getId(), third.getId(), ReadingStatus.READ);
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.WANT);
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		// 猫だけ新しくする。こころと三四郎は同じ日時
		this.jdbc.update("UPDATE reading_statuses SET updated_at = ? WHERE book_id <> ?",
				Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), this.neko.getId());

		List<ReadingStatuses.ShelfItem> all = this.statuses.page(this.taro.getId(), ReadingStatus.READ, null, 21);
		assertThat(all).extracting((item) -> item.book().id())
			.containsExactly(this.neko.getId(), third.getId(), this.kokoro.getId());

		List<ReadingStatuses.ShelfItem> rest = this.statuses.page(this.taro.getId(), ReadingStatus.READ,
				ShelfService.cursorOf(all.get(1)), 21);
		assertThat(rest).extracting((item) -> item.book().id()).containsExactly(this.kokoro.getId());
		assertThat(this.statuses.page(this.taro.getId(), ReadingStatus.READ, null, 1)).hasSize(1);
		assertThat(this.statuses.page(this.taro.getId(), ReadingStatus.WANT, null, 21)).isEmpty();
		assertThat(this.statuses.page(this.hanako.getId(), ReadingStatus.READ, null, 21)).isEmpty();
	}

	// TC-08-6：その人がその本に書いたレビューの星。書いていなければ null（ほかの人の星は出さない）
	@Test
	void 本の情報と自分のレビューの星() {
		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		this.statuses.set(this.taro.getId(), this.kokoro.getId(), ReadingStatus.READ);
		this.jdbc.update("INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 4, '感想')",
				this.taro.getId(), this.neko.getId());
		this.jdbc.update("INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 2, '感想')",
				this.hanako.getId(), this.kokoro.getId());

		List<ReadingStatuses.ShelfItem> items = this.statuses.page(this.taro.getId(), ReadingStatus.READ, null, 21);
		ReadingStatuses.ShelfItem neko = items.stream()
			.filter((item) -> item.book().id() == this.neko.getId())
			.findFirst()
			.orElseThrow();
		ReadingStatuses.ShelfItem kokoro = items.stream()
			.filter((item) -> item.book().id() == this.kokoro.getId())
			.findFirst()
			.orElseThrow();
		assertThat(neko.book()).isEqualTo(new BookSummary(this.neko.getId(), "吾輩は猫である", List.of("夏目漱石"), null));
		assertThat(neko.status()).isEqualTo(ReadingStatus.READ);
		assertThat(neko.rating()).isEqualTo(4);
		assertThat(kokoro.book().authors()).isEmpty();
		assertThat(kokoro.rating()).isNull();
	}

	// FN-11 の bookshelfCounts：その人の分だけ数え、1 冊もないステータスは 0
	@Test
	void ステータスごとの冊数を数える() {
		assertThat(this.statuses.counts(this.taro.getId())).isEqualTo(new BookshelfCounts(0, 0, 0));

		this.statuses.set(this.taro.getId(), this.neko.getId(), ReadingStatus.READ);
		this.statuses.set(this.taro.getId(), this.kokoro.getId(), ReadingStatus.READ);
		this.statuses.set(this.hanako.getId(), this.neko.getId(), ReadingStatus.WANT);

		assertThat(this.statuses.counts(this.taro.getId())).isEqualTo(new BookshelfCounts(0, 0, 2));
		assertThat(this.statuses.counts(this.hanako.getId())).isEqualTo(new BookshelfCounts(1, 0, 0));
	}

	private Instant updatedAt() {
		return this.jdbc.queryForObject("SELECT updated_at FROM reading_statuses", Timestamp.class).toInstant();
	}

	private static Book book(String googleBooksId, String title, List<String> authors) {
		return new Book(new BookData(googleBooksId, null, title, authors, null, null, null, null), NOW);
	}

}
