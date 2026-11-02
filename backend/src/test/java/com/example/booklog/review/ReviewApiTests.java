package com.example.booklog.review;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.jayway.jsonpath.JsonPath;

// レビュー（FN-03）と本のページの平均評価・レビューの一覧（FN-10）を、API を通して（Controller から DB まで）確かめる。
// 10 章の「テストの観点」のうち、サーバー側で確かめられるもの（写真・参考になった・コメントは、その機能を作るときに足す）
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class ReviewApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	private long taro;

	private long hanako;

	private long neko;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, reading_statuses RESTART IDENTITY CASCADE");
		this.clock.reset();
		this.taro = user("taro_y");
		this.hanako = user("hanako");
		this.neko = book("a", "吾輩は猫である");
	}

	// RV-2・RV-5、TC-03-4
	@Test
	void 投稿すると本棚になければ読了で入る() {
		MvcTestResult result = post(this.taro, this.neko, "4", "面白かった", "false");

		assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
				{"author":{"username":"taro_y","displayName":"taro_yの表示名"},
				 "book":{"title":"吾輩は猫である","authors":["夏目漱石"],"thumbnailUrl":null},
				 "rating":4,"body":"面白かった","spoiler":false,"photos":[],"mine":true,"editedAt":null}
				""");
		assertThat(status(this.taro, this.neko)).isEqualTo("READ");
	}

	// TC-03-4
	@Test
	void 読書中の本に書いてもステータスは変えない() {
		this.jdbc.update("INSERT INTO reading_statuses (user_id, book_id, status) VALUES (?, ?, 'READING')", this.taro,
				this.neko);
		assertThat(post(this.taro, this.neko, "4", "途中まで", "false")).hasStatus(HttpStatus.CREATED);
		assertThat(status(this.taro, this.neko)).isEqualTo("READING");
	}

	// TC-03-3
	@Test
	void 同じ本に二つ目のレビューは409で本の詳細に自分のレビューのIDが入る() {
		long id = id(post(this.taro, this.neko, "4", "一回目", "false"));
		assertError(post(this.taro, this.neko, "5", "二回目", "false"), ErrorCode.REVIEW_EXISTS);

		assertThat(bookDetail(this.taro)).bodyJson().extractingPath("$.myReviewId").isEqualTo((int) id);
		assertThat(bookDetail(this.hanako)).bodyJson().extractingPath("$.myReviewId").isNull();
	}

	// TC-03-2：CRLF で送っても LF にそろえてから数える
	@Test
	void 感想の改行はLFにそろえて保存する() {
		assertThat(post(this.taro, this.neko, "3", "一行目\r\n二行目\r\n", "false")).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.body")
			.isEqualTo("一行目\n二行目");
		assertError(post(this.hanako, this.neko, "3", "あ".repeat(2001), "false"), ErrorCode.BAD_REQUEST, "body");
		assertError(post(this.hanako, this.neko, "3", " \n ", "false"), ErrorCode.BODY_REQUIRED, "body");
	}

	// TC-03-1：画面を通さずに送られた星
	@Test
	void 星が0や6や小数なら400() {
		assertError(post(this.taro, this.neko, "0", "感想", "false"), ErrorCode.BAD_REQUEST, "rating");
		assertError(post(this.taro, this.neko, "6", "感想", "false"), ErrorCode.BAD_REQUEST, "rating");
		assertError(post(this.taro, this.neko, "3.5", "感想", "false"), ErrorCode.BAD_REQUEST);
		assertThat(count("reviews")).isZero();
	}

	// TC-03-10
	@Test
	void 感想のscriptは文字のまま返す() {
		assertThat(post(this.taro, this.neko, "3", "<script>alert(1)</script>", "false")).bodyJson()
			.extractingPath("$.body")
			.isEqualTo("<script>alert(1)</script>");
	}

	// TC-03-5・TC-03-9
	@Test
	void 編集すると編集日時が入り投稿日時と並び順は変わらない() {
		long first = id(post(this.taro, this.neko, "4", "一回目", "false"));
		long second = id(post(this.hanako, this.neko, "3", "はなこ", "false"));
		String createdAt = JsonPath.read(body(get(this.taro, first)), "$.createdAt");
		this.clock.advance(Duration.ofMinutes(5));

		MvcTestResult edited = patch(this.taro, first, "2", "読み直した", "true");

		assertThat(edited).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"rating":2,"body":"読み直した","spoiler":true}
				""");
		assertThat(edited).bodyJson().extractingPath("$.createdAt").isEqualTo(createdAt);
		assertThat(edited).bodyJson().extractingPath("$.editedAt").isNotNull();
		assertThat(reviewIds(this.taro)).containsExactly((int) second, (int) first);
	}

	// TC-03-6
	@Test
	void 他人のレビューは編集も削除もできない() {
		long id = id(post(this.taro, this.neko, "4", "たろう", "false"));
		assertError(patch(this.hanako, id, "1", "書き換え", "false"), ErrorCode.FORBIDDEN);
		assertError(delete(this.hanako, id), ErrorCode.FORBIDDEN);
		assertThat(get(this.hanako, id)).bodyJson().extractingPath("$.body").isEqualTo("たろう");
	}

	// TC-03-7・TC-03-12
	@Test
	void 削除すると本と本棚は残り詳細はM20() {
		long id = id(post(this.taro, this.neko, "4", "消す", "false"));

		assertThat(delete(this.taro, id)).hasStatus(HttpStatus.NO_CONTENT);

		assertError(get(this.taro, id), ErrorCode.REVIEW_NOT_FOUND);
		assertError(delete(this.taro, id), ErrorCode.REVIEW_NOT_FOUND);
		assertThat(count("books")).isEqualTo(1);
		assertThat(status(this.taro, this.neko)).isEqualTo("READ");
	}

	// TC-10-1・TC-10-2
	@Test
	void 平均評価は小数点以下1桁でレビューを書くと消すと変わる() {
		assertThat(bookDetail(this.taro)).bodyJson().isLenientlyEqualTo("""
				{"averageRating":null,"reviewCount":0}
				""");
		long jiro = user("jiro");
		post(this.taro, this.neko, "4", "a", "false");
		post(this.hanako, this.neko, "4", "b", "false");
		long last = id(post(jiro, this.neko, "5", "c", "false"));
		assertThat(bookDetail(this.taro)).bodyJson().isLenientlyEqualTo("""
				{"averageRating":4.3,"reviewCount":3}
				""");

		delete(jiro, last);
		assertThat(bookDetail(this.taro)).bodyJson().isLenientlyEqualTo("""
				{"averageRating":4.0,"reviewCount":2}
				""");
	}

	// TC-10-1
	@Test
	void 星1つだけなら平均は1() {
		post(this.taro, this.neko, "1", "a", "false");
		assertThat(bookDetail(this.taro)).bodyJson().extractingPath("$.averageRating").isEqualTo(1.0);
	}

	// BK-5：20 件ずつ、新しい順
	@Test
	void 本のレビューの一覧を20件ずつ読む() {
		for (int i = 0; i < 21; i++) {
			post(user("user" + i), this.neko, "3", "感想 " + i, "false");
		}
		MvcTestResult first = list(this.taro, null);
		List<Integer> firstIds = JsonPath.read(body(first), "$.items[*].id");
		String cursor = JsonPath.read(body(first), "$.nextCursor");
		assertThat(firstIds).hasSize(20).isSortedAccordingTo((a, b) -> b - a);
		assertThat(cursor).isEqualTo(String.valueOf(firstIds.getLast()));

		MvcTestResult second = list(this.taro, cursor);
		assertThat(second).bodyJson().extractingPath("$.items[*].body").isEqualTo(List.of("感想 0"));
		assertThat(second).bodyJson().extractingPath("$.nextCursor").isNull();
	}

	@Test
	void ない本にはレビューを書けず一覧も404() {
		assertError(post(this.taro, 999, "4", "感想", "false"), ErrorCode.BOOK_NOT_FOUND);
		assertError(listOf(999), ErrorCode.BOOK_NOT_FOUND);
	}

	// 退会（1-12）に備え、ユーザーを消すとレビューと本棚も一緒に消える（データ設計書 方針 7）
	@Test
	void ユーザーを消すとレビューと本棚も消え本は残る() {
		post(this.taro, this.neko, "4", "感想", "false");
		this.jdbc.update("DELETE FROM users WHERE id = ?", this.taro);
		assertThat(count("reviews")).isZero();
		assertThat(count("reading_statuses")).isZero();
		assertThat(count("books")).isEqualTo(1);
	}

	private MvcTestResult post(long userId, long bookId, String rating, String body, String spoiler) {
		return this.mvc.post()
			.uri("/api/books/{bookId}/reviews", bookId)
			.multipart()
			.param("rating", rating)
			.param("body", body)
			.param("spoiler", spoiler)
			.with(loggedIn(userId))
			.with(csrf())
			.exchange();
	}

	private MvcTestResult patch(long userId, long id, String rating, String body, String spoiler) {
		return this.mvc.perform(multipart(HttpMethod.PATCH, "/api/reviews/{id}", id).param("rating", rating)
			.param("body", body)
			.param("spoiler", spoiler)
			.with(loggedIn(userId))
			.with(csrf()));
	}

	private MvcTestResult get(long userId, long id) {
		return this.mvc.get().uri("/api/reviews/{id}", id).with(loggedIn(userId)).exchange();
	}

	private MvcTestResult delete(long userId, long id) {
		return this.mvc.delete().uri("/api/reviews/{id}", id).with(loggedIn(userId)).with(csrf()).exchange();
	}

	private MvcTestResult list(long userId, String cursor) {
		return this.mvc.get()
			.uri((cursor == null) ? "/api/books/{bookId}/reviews" : "/api/books/{bookId}/reviews?cursor=" + cursor,
					this.neko)
			.with(loggedIn(userId))
			.exchange();
	}

	private MvcTestResult listOf(long bookId) {
		return this.mvc.get().uri("/api/books/{bookId}/reviews", bookId).with(loggedIn(this.taro)).exchange();
	}

	private MvcTestResult bookDetail(long userId) {
		return this.mvc.get().uri("/api/books/{id}", this.neko).with(loggedIn(userId)).exchange();
	}

	// 本の一覧に出ているレビューの ID（新しい順）
	private List<Integer> reviewIds(long userId) {
		return JsonPath.read(body(list(userId, null)), "$.items[*].id");
	}

	private long user(String username) {
		return this.jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash) VALUES (?, ?, 'hash') RETURNING id",
				Long.class, username, username + "の表示名");
	}

	private long book(String googleBooksId, String title) {
		return this.jdbc.queryForObject(
				"INSERT INTO books (google_books_id, title, authors) VALUES (?, ?, ARRAY['夏目漱石']) RETURNING id",
				Long.class, googleBooksId, title);
	}

	private String status(long userId, long bookId) {
		return this.jdbc.queryForObject("SELECT status FROM reading_statuses WHERE user_id = ? AND book_id = ?",
				String.class, userId, bookId);
	}

	private int count(String table) {
		Integer count = this.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
		return (count == null) ? 0 : count;
	}

	private static String body(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private static long id(MvcTestResult result) {
		return JsonPath.<Number>read(body(result), "$.id").longValue();
	}

}
