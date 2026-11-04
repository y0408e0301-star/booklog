package com.example.booklog.helpful;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.example.booklog.RepositoryTest;
import com.example.booklog.user.User;

// 参考になった（helpfuls）の SQL と制約（FN-05 5 章）
@RepositoryTest
class HelpfulsRepositoryTests {

	private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	@Autowired
	TestEntityManager em;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	NamedParameterJdbcTemplate named;

	private Helpfuls helpfuls;

	private long taro;

	private long hanako;

	private long first;

	private long second;

	@BeforeEach
	void setUp() {
		this.helpfuls = new Helpfuls(this.named);
		this.taro = this.em.persist(new User("taro_y", "たろう", "hash", NOW)).getId();
		this.hanako = this.em.persist(new User("hanako", "はなこ", "hash", NOW)).getId();
		this.first = review(this.taro, "a");
		this.second = review(this.taro, "b");
	}

	// H-3・5 章の 1：何回付けても 1 つ。ないときに取り消しても何もしない
	@Test
	void 何回付けても一つで何回取り消しても何もしない() {
		this.helpfuls.add(this.hanako, this.first);
		this.helpfuls.add(this.hanako, this.first);
		assertThat(this.helpfuls.count(this.first)).isOne();

		this.helpfuls.remove(this.hanako, this.first);
		this.helpfuls.remove(this.hanako, this.first);
		assertThat(this.helpfuls.count(this.first)).isZero();
	}

	@Test
	void レビューを書いた人() {
		assertThat(this.helpfuls.authorOf(this.first)).contains(this.taro);
		assertThat(this.helpfuls.authorOf(999)).isEmpty();
	}

	// 一覧の件数と押したかを、まとめて 1 回で数える。0 件のレビューは結果に入らない
	@Test
	void 件数と押したかをまとめて数える() {
		this.helpfuls.add(this.hanako, this.first);
		long jiro = this.em.persist(new User("jiro", "じろう", "hash", NOW)).getId();
		this.helpfuls.add(jiro, this.first);

		assertThat(this.helpfuls.counts(List.of(this.first, this.second))).isEqualTo(Map.of(this.first, 2L));
		assertThat(this.helpfuls.markedBy(this.hanako, List.of(this.first, this.second))).containsExactly(this.first);
		assertThat(this.helpfuls.markedBy(this.taro, List.of(this.first, this.second))).isEmpty();
	}

	// TC-05-5（5 章の 3）：レビューを消すと一緒に消え、押した人が退会すると件数が減る
	@Test
	void レビューを消すと消え押した人が退会すると減る() {
		this.helpfuls.add(this.hanako, this.first);
		this.helpfuls.add(this.hanako, this.second);
		long jiro = this.em.persist(new User("jiro", "じろう", "hash", NOW)).getId();
		this.helpfuls.add(jiro, this.second);

		this.jdbc.update("DELETE FROM reviews WHERE id = ?", this.first);
		this.jdbc.update("DELETE FROM users WHERE id = ?", jiro);

		assertThat(
				this.jdbc.queryForObject("SELECT count(*) FROM helpfuls WHERE review_id = ?", Long.class, this.first))
			.isZero();
		assertThat(this.helpfuls.count(this.second)).isOne();
	}

	private long review(long userId, String googleBooksId) {
		long bookId = this.jdbc.queryForObject(
				"INSERT INTO books (google_books_id, title) VALUES (?, '本') RETURNING id", Long.class, googleBooksId);
		return this.jdbc.queryForObject(
				"INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 3, '感想') RETURNING id", Long.class,
				userId, bookId);
	}

}
