package com.example.booklog.review;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManagerFactory;

// タイムライン（機能定義書 FN-09）を、API を通して（Controller から DB まで）確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class TimelineApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	private long taro;

	private long hanako;

	private long jiro;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, reading_statuses, follows RESTART IDENTITY CASCADE");
		this.taro = user("taro_y");
		this.hanako = user("hanako");
		this.jiro = user("jiro");
	}

	// TC-09-1
	@Test
	void フォロー中は自分とフォローした人のレビューですべては全員() {
		long mine = review(this.taro, "たろう");
		long followed = review(this.hanako, "はなこ");
		long other = review(this.jiro, "じろう");
		follow(this.taro, this.hanako);

		assertThat(ids(timeline(this.taro, "following", null))).containsExactly(followed, mine);
		assertThat(ids(timeline(this.taro, "all", null))).containsExactly(other, followed, mine);
		assertThat(timeline(this.taro, "all", null)).bodyJson().extractingPath("$.items[0].book.title").isNotNull();
	}

	// TC-09-2（T-2・T-7）
	@Test
	void 四十一件は二十と四十と四十一件で読め途中で投稿があっても重ならず抜けない() {
		List<Long> written = new ArrayList<>();
		for (int i = 0; i < 41; i++) {
			written.add(review(this.hanako, "感想 " + i));
		}
		List<Long> expected = written.reversed();

		MvcTestResult first = timeline(this.taro, "all", null);
		// 1 ページ目を読んだ後にほかの人が投稿しても、続きには出ない（先頭に出る）
		long newer = review(this.jiro, "後から");
		MvcTestResult second = timeline(this.taro, "all", cursor(first));
		MvcTestResult third = timeline(this.taro, "all", cursor(second));

		List<Long> read = new ArrayList<>();
		read.addAll(ids(first));
		read.addAll(ids(second));
		read.addAll(ids(third));
		assertThat(ids(first)).hasSize(20);
		assertThat(ids(second)).hasSize(20);
		assertThat(ids(third)).hasSize(1);
		assertThat(read).isEqualTo(expected).doesNotContain(newer);
		assertThat(cursor(third)).isNull();
		assertThat(ids(timeline(this.taro, "all", null)).getFirst()).isEqualTo(newer);
	}

	// TC-09-4（T-4）
	@Test
	void 誰もフォローしておらず自分のレビューもなければフォロー中は空() {
		review(this.hanako, "はなこ");
		assertThat(timeline(this.taro, "following", null)).bodyJson().isStrictlyEqualTo("""
				{"items":[],"nextCursor":null}
				""");
	}

	// TC-09-5（T-5）
	@Test
	void 編集したレビューの位置は変わらない() {
		long old = review(this.taro, "古い");
		long newer = review(this.hanako, "新しい");
		this.mvc.perform(multipart(HttpMethod.PATCH, "/api/reviews/{id}", old).param("rating", "1")
			.param("body", "編集した")
			.with(loggedIn(this.taro))
			.with(csrf()));

		assertThat(ids(timeline(this.taro, "all", null))).containsExactly(newer, old);
	}

	// TC-09-6：20 件を出すときの問い合わせの回数が、件数によって増えない（N+1 がない）
	@Test
	void 問い合わせの回数は件数によって増えない() {
		Statistics statistics = this.entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		try {
			review(this.hanako, "1 件目");
			statistics.clear();
			timeline(this.taro, "all", null);
			long one = statistics.getPrepareStatementCount();

			for (int i = 0; i < 19; i++) {
				review(user("user" + i), "感想 " + i);
			}
			statistics.clear();
			assertThat(ids(timeline(this.taro, "all", null))).hasSize(20);
			assertThat(statistics.getPrepareStatementCount()).isEqualTo(one);
		}
		finally {
			statistics.setStatisticsEnabled(false);
		}
	}

	@Test
	void タブがなければ400() {
		assertError(this.mvc.get().uri("/api/timeline").with(loggedIn(this.taro)).exchange(), ErrorCode.BAD_REQUEST);
		assertError(timeline(this.taro, "mine", null), ErrorCode.BAD_REQUEST);
	}

	private MvcTestResult timeline(long userId, String tab, String cursor) {
		String uri = "/api/timeline?tab=" + tab + ((cursor == null) ? "" : "&cursor=" + cursor);
		return this.mvc.get().uri(uri).with(loggedIn(userId)).exchange();
	}

	// 本は 1 人 1 冊に 1 つなので、レビューごとに本を作る
	private long review(long userId, String body) {
		long bookId = this.jdbc.queryForObject(
				"INSERT INTO books (google_books_id, title) VALUES (gen_random_uuid()::text, '本') RETURNING id",
				Long.class);
		return this.jdbc.queryForObject(
				"INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 3, ?) RETURNING id", Long.class,
				userId, bookId, body);
	}

	private void follow(long followerId, long followeeId) {
		this.jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", followerId, followeeId);
	}

	private long user(String username) {
		return this.jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash) VALUES (?, ?, 'hash') RETURNING id",
				Long.class, username, username + "の表示名");
	}

	private static String body(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private static List<Long> ids(MvcTestResult result) {
		List<Number> ids = JsonPath.read(body(result), "$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private static String cursor(MvcTestResult result) {
		return JsonPath.read(body(result), "$.nextCursor");
	}

}
