package com.example.booklog.helpful;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;

// 参考になった（機能定義書 FN-05）を、API を通して（Controller から DB まで）確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class HelpfulApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	private long taro;

	private long hanako;

	private long review;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, helpfuls RESTART IDENTITY CASCADE");
		this.taro = user("taro_y");
		this.hanako = user("hanako");
		long book = this.jdbc
			.queryForObject("INSERT INTO books (google_books_id, title) VALUES ('g', 'こころ') RETURNING id", Long.class);
		this.review = this.jdbc.queryForObject(
				"INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 4, '感想') RETURNING id", Long.class,
				this.taro, book);
	}

	// H-1・H-2・H-5：付けると件数が増え、レビューの詳細の helpfulCount・helpfulByMe にも出る
	@Test
	void 付けて取り消すと件数と押したかが変わる() {
		assertThat(mark(this.hanako)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"helpful":true,"helpfulCount":1}
				""");
		assertThat(detail(this.hanako)).bodyJson().extractingPath("$.helpfulCount").isEqualTo(1);
		assertThat(detail(this.hanako)).bodyJson().extractingPath("$.helpfulByMe").isEqualTo(true);
		// 書いた人から見ても件数は同じで、押したかは false
		assertThat(detail(this.taro)).bodyJson().extractingPath("$.helpfulByMe").isEqualTo(false);
		assertThat(timeline(this.taro)).bodyJson().extractingPath("$.items[0].helpfulCount").isEqualTo(1);

		assertThat(unmark(this.hanako)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"helpful":false,"helpfulCount":0}
				""");
		assertThat(detail(this.hanako)).bodyJson().extractingPath("$.helpfulByMe").isEqualTo(false);
	}

	// TC-05-1・5 章の 2：何回送っても同じ結果
	@Test
	void 何回送っても行は0か1() {
		for (int i = 0; i < 5; i++) {
			assertThat(mark(this.hanako)).hasStatusOk();
		}
		assertThat(rows()).isOne();
		for (int i = 0; i < 5; i++) {
			assertThat(unmark(this.hanako)).hasStatusOk();
		}
		assertThat(rows()).isZero();
	}

	// TC-05-2：同じ人が同時に付けても行は 1 つ
	@Test
	void 同時に付けても行は一つ() throws Exception {
		Callable<MvcTestResult> task = () -> mark(this.hanako);
		try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
			Future<?>[] results = new Future<?>[8];
			for (int i = 0; i < results.length; i++) {
				results[i] = executor.submit(task);
			}
			for (Future<?> result : results) {
				assertThat((MvcTestResult) result.get()).hasStatusOk();
			}
		}
		assertThat(rows()).isOne();
	}

	// TC-05-3（H-4）
	@Test
	void 自分のレビューには付けられない() {
		assertError(mark(this.taro), ErrorCode.CANNOT_MARK_OWN_REVIEW);
		assertThat(rows()).isZero();
	}

	@Test
	void 削除されたレビューは404() {
		this.jdbc.update("DELETE FROM reviews WHERE id = ?", this.review);
		assertError(mark(this.hanako), ErrorCode.REVIEW_NOT_FOUND);
		assertError(unmark(this.hanako), ErrorCode.REVIEW_NOT_FOUND);
	}

	// TC-05-5：レビューを削除すると helpfuls の行も消える
	@Test
	void レビューを削除すると参考になったも消える() {
		mark(this.hanako);
		assertThat(this.mvc.delete().uri("/api/reviews/{id}", this.review).with(loggedIn(this.taro)).with(csrf()))
			.hasStatus2xxSuccessful();
		assertThat(rows()).isZero();
	}

	private MvcTestResult mark(long userId) {
		return this.mvc.put()
			.uri("/api/reviews/{id}/helpful", this.review)
			.with(loggedIn(userId))
			.with(csrf())
			.exchange();
	}

	private MvcTestResult unmark(long userId) {
		return this.mvc.delete()
			.uri("/api/reviews/{id}/helpful", this.review)
			.with(loggedIn(userId))
			.with(csrf())
			.exchange();
	}

	private MvcTestResult detail(long userId) {
		return this.mvc.get().uri("/api/reviews/{id}", this.review).with(loggedIn(userId)).exchange();
	}

	private MvcTestResult timeline(long userId) {
		return this.mvc.get().uri("/api/timeline?tab=all").with(loggedIn(userId)).exchange();
	}

	private long rows() {
		return this.jdbc.queryForObject("SELECT count(*) FROM helpfuls", Long.class);
	}

	private long user(String username) {
		return this.jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash) VALUES (?, ?, 'hash') RETURNING id",
				Long.class, username, username + "の表示名");
	}

}
