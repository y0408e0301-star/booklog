package com.example.booklog.comment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.booklog.RepositoryTest;
import com.example.booklog.user.User;

// コメント（comments）の SQL と制約（FN-06 5 章）
@RepositoryTest
class CommentRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	@Autowired
	CommentRepository comments;

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	private User taro;

	private User hanako;

	private long first;

	private long second;

	@BeforeEach
	void setUp() {
		this.taro = this.em.persist(new User("taro_y", "たろう", "hash", NOW));
		this.hanako = this.em.persist(new User("hanako", "はなこ", "hash", NOW));
		this.first = review(this.taro, "a");
		this.second = review(this.taro, "b");
	}

	// C-3・5 章の 1：そのレビューのコメントだけを、cursor より後ろから古い順に
	@Test
	void そのレビューのコメントを古い順に読む() {
		long one = save(this.first, this.hanako, "1 件目");
		save(this.second, this.hanako, "ほかのレビュー");
		long two = save(this.first, this.taro, "2 件目");
		this.em.clear();

		assertThat(this.comments.findAfter(this.first, 0, Limit.of(51))).extracting(Comment::getId)
			.containsExactly(one, two);
		assertThat(this.comments.findAfter(this.first, one, Limit.of(51))).extracting(Comment::getId)
			.containsExactly(two);
		assertThat(this.comments.findAfter(this.first, 0, Limit.of(1))).hasSize(1);
	}

	@Test
	void コメント数をまとめて数える() {
		save(this.first, this.hanako, "a");
		save(this.first, this.taro, "b");
		long third = review(this.hanako, "c");
		assertThat(new CommentCounts(this.comments).of(List.of(this.first, this.second, third)))
			.isEqualTo(Map.of(this.first, 2L));
	}

	@Test
	void レビューがあるか() {
		assertThat(this.comments.reviewExists(this.first)).isTrue();
		assertThat(this.comments.reviewExists(999)).isFalse();
	}

	// TC-06-7（5 章の 2）：レビューを消すとコメントも消え、コメントした人が退会するとそのコメントが消える
	@Test
	void レビューを消すと消え書いた人が退会すると消える() {
		save(this.first, this.hanako, "a");
		save(this.second, this.hanako, "b");
		save(this.second, this.taro, "c");
		this.em.clear();

		this.jdbc.update("DELETE FROM reviews WHERE id = ?", this.first);
		this.jdbc.update("DELETE FROM users WHERE id = ?", this.hanako.getId());

		assertThat(this.comments.findAll()).extracting(Comment::getBody).containsExactly("c");
	}

	private long save(long reviewId, User author, String body) {
		return this.comments.saveAndFlush(new Comment(reviewId, author, body, NOW)).getId();
	}

	private long review(User author, String googleBooksId) {
		long bookId = this.jdbc.queryForObject(
				"INSERT INTO books (google_books_id, title) VALUES (?, '本') RETURNING id", Long.class, googleBooksId);
		return this.jdbc.queryForObject(
				"INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 3, '感想') RETURNING id", Long.class,
				author.getId(), bookId);
	}

}
