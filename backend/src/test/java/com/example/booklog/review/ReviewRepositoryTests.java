package com.example.booklog.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.booklog.RepositoryTest;
import com.example.booklog.book.Book;
import com.example.booklog.book.BookData;
import com.example.booklog.follow.Follow;
import com.example.booklog.shelf.ReadingStatuses;
import com.example.booklog.user.User;

// レビュー（reviews）と読書ステータス（reading_statuses）の SQL と制約
@RepositoryTest
class ReviewRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	@Autowired
	ReviewRepository reviews;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	private User taro;

	private User hanako;

	private Book neko;

	private Book kokoro;

	@BeforeEach
	void setUp() {
		this.taro = this.em.persist(new User("taro_y", "たろう", "hash", NOW));
		this.hanako = this.em.persist(new User("hanako", "はなこ", "hash", NOW));
		this.neko = this.em.persist(book("a", "吾輩は猫である"));
		this.kokoro = this.em.persist(book("b", "こころ"));
	}

	// BK-5：その本のレビューだけを、cursor より古いものから新しい順に
	@Test
	void 本のレビューを新しい順に読む() {
		long first = save(this.taro, this.neko, 4).getId();
		save(this.taro, this.kokoro, 5);
		long second = save(this.hanako, this.neko, 3).getId();
		this.em.clear();

		assertThat(this.reviews.findByBookBefore(this.neko.getId(), Long.MAX_VALUE, Limit.of(21)))
			.extracting(Review::getId)
			.containsExactly(second, first);
		assertThat(this.reviews.findByBookBefore(this.neko.getId(), second, Limit.of(21))).extracting(Review::getId)
			.containsExactly(first);
		assertThat(this.reviews.findByBookBefore(this.neko.getId(), Long.MAX_VALUE, Limit.of(1))).hasSize(1);
	}

	// TC-09-1（T-1）
	@Test
	void フォロー中は自分とフォローした人のレビューだけですべては全員() {
		User jiro = this.em.persist(new User("jiro", "じろう", "hash", NOW));
		long mine = save(this.taro, this.neko, 4).getId();
		long followed = save(this.hanako, this.neko, 3).getId();
		long other = save(jiro, this.neko, 5).getId();
		this.em.persist(new Follow(this.taro.getId(), this.hanako.getId(), NOW));
		this.em.flush();
		this.em.clear();

		assertThat(this.reviews.findFollowingBefore(this.taro.getId(), Long.MAX_VALUE, Limit.of(21)))
			.extracting(Review::getId)
			.containsExactly(followed, mine);
		assertThat(this.reviews.findAllBefore(Long.MAX_VALUE, Limit.of(21))).extracting(Review::getId)
			.containsExactly(other, followed, mine);
		assertThat(this.reviews.findAllBefore(other, Limit.of(1))).extracting(Review::getId).containsExactly(followed);
		// フォローされた側からは、フォローした人のレビューは見えない（自分の分だけ）
		assertThat(this.reviews.findFollowingBefore(this.hanako.getId(), Long.MAX_VALUE, Limit.of(21)))
			.extracting(Review::getId)
			.containsExactly(followed);
	}

	// F-3（データ設計書 4.7）
	@Test
	void 自分自身はフォローできない() {
		assertThatThrownBy(() -> this.jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
				this.taro.getId(), this.taro.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// F-4（データ設計書 4.7）
	@Test
	void 同じ人を二回フォローできない() {
		this.jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", this.taro.getId(),
				this.hanako.getId());
		assertThatThrownBy(() -> this.jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)",
				this.taro.getId(), this.hanako.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 書いた人と本も一緒に読む() {
		long id = save(this.taro, this.neko, 4).getId();
		this.em.clear();
		Review review = this.reviews.findWithAuthorAndBookById(id).orElseThrow();
		assertThat(review.getAuthor().getUsername()).isEqualTo("taro_y");
		assertThat(review.getBook().getTitle()).isEqualTo("吾輩は猫である");
		assertThat(this.reviews.existsByAuthorAndBook(this.taro.getId(), this.neko.getId())).isTrue();
		assertThat(this.reviews.existsByAuthorAndBook(this.hanako.getId(), this.neko.getId())).isFalse();
	}

	// BR-2：1 人 1 冊に 1 つ
	@Test
	void 同じ人が同じ本に二つ目のレビューは書けない() {
		save(this.taro, this.neko, 4);
		assertThatThrownBy(() -> save(this.taro, this.neko, 5)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 星は1から5までしか保存できない() {
		assertThatThrownBy(
				() -> this.jdbc.update("INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 6, 'x')",
						this.taro.getId(), this.neko.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// 感想の 2,000 文字は文字（コードポイント）で数える（データ設計書 4.3）
	@Test
	void 絵文字2000文字の感想を保存できる() {
		long id = this.reviews
			.saveAndFlush(new Review(this.taro, this.neko, new ReviewInput(3, "😀".repeat(2000), false), NOW))
			.getId();
		this.em.clear();
		assertThat(this.reviews.findById(id).orElseThrow().getBody()).hasSize(4000);
	}

	// TC-03-4（RV-5、BR-10）
	@Test
	void 本棚になければ読了で入れもうあればステータスを変えない() {
		ReadingStatuses statuses = new ReadingStatuses(this.jdbc);
		this.jdbc.update("INSERT INTO reading_statuses (user_id, book_id, status) VALUES (?, ?, 'READING')",
				this.taro.getId(), this.kokoro.getId());

		statuses.addReadIfAbsent(this.taro.getId(), this.neko.getId());
		statuses.addReadIfAbsent(this.taro.getId(), this.kokoro.getId());

		assertThat(status(this.taro, this.neko)).isEqualTo("READ");
		assertThat(status(this.taro, this.kokoro)).isEqualTo("READING");
	}

	private Review save(User author, Book book, int rating) {
		return this.reviews.saveAndFlush(new Review(author, book, new ReviewInput(rating, "感想", false), NOW));
	}

	private String status(User user, Book book) {
		return this.jdbc.queryForObject("SELECT status FROM reading_statuses WHERE user_id = ? AND book_id = ?",
				String.class, user.getId(), book.getId());
	}

	private static Book book(String googleBooksId, String title) {
		return new Book(new BookData(googleBooksId, null, title, List.of(), null, null, null, null), NOW);
	}

}
