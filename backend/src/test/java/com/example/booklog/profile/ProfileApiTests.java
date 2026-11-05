package com.example.booklog.profile;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.jayway.jsonpath.JsonPath;

// プロフィール（機能定義書 FN-11）を、API を通して（Controller から DB まで）確かめる。
// 10 章の「テストの観点」のうち、サーバー側で確かめられるもの
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ProfileApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	private long taro;

	private long hanako;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, reading_statuses, follows RESTART IDENTITY CASCADE");
		this.taro = user("taro_y");
		this.hanako = user("hanako");
	}

	// TC-11-1：件数がデータベースの件数と合う
	@Test
	void 件数はデータベースの件数と合う() {
		review(this.hanako, "感想");
		review(this.hanako, "感想");
		review(this.taro, "感想");
		follow(this.taro, this.hanako);
		follow(this.hanako, this.taro);
		follow(user("jiro"), this.hanako);
		status(this.hanako, "WANT");
		status(this.hanako, "READ");
		status(this.hanako, "READ");
		status(this.taro, "READING");

		assertThat(get(this.taro, "hanako")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"username":"hanako","displayName":"hanakoの表示名","bio":"","reviewCount":2,"followingCount":1,
				 "followerCount":2,"bookshelfCounts":{"WANT":1,"READING":0,"READ":2},"followedByMe":true,"me":false}
				""");
	}

	@Test
	void 自分のプロフィールはmeがtrue() {
		assertThat(get(this.taro, "TARO_Y")).bodyJson().extractingPath("$.me").isEqualTo(true);
		assertThat(get(this.taro, "taro_y")).bodyJson().extractingPath("$.followedByMe").isEqualTo(false);
		assertThat(get(this.taro, "hanako")).bodyJson().extractingPath("$.me").isEqualTo(false);
	}

	// TC-11-5：退会した人は行が消えるので、いない人と同じになる
	@Test
	void いないユーザーと退会した人は404() {
		assertError(get(this.taro, "nobody"), ErrorCode.USER_NOT_FOUND);
		this.jdbc.update("DELETE FROM users WHERE id = ?", this.hanako);
		assertError(get(this.taro, "hanako"), ErrorCode.USER_NOT_FOUND);
		assertError(reviews(this.taro, "hanako", null), ErrorCode.USER_NOT_FOUND);
	}

	// PF-6：変えられるのはログインした人のプロフィールだけ。改行は LF にそろえる
	@Test
	void 変えると本人のプロフィールだけが変わる() {
		assertThat(edit(this.taro, "{\"displayName\":\" 新しい名前 \",\"bio\":\"一行目\\r\\n二行目\"}")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.bio")
			.isEqualTo("一行目\n二行目");
		assertThat(get(this.hanako, "taro_y")).bodyJson().extractingPath("$.displayName").isEqualTo("新しい名前");
		assertThat(get(this.hanako, "taro_y")).bodyJson().extractingPath("$.bio").isEqualTo("一行目\n二行目");
		assertThat(get(this.taro, "hanako")).bodyJson().extractingPath("$.displayName").isEqualTo("hanakoの表示名");
	}

	// TC-11-3
	@Test
	void 文字数の境目() {
		assertError(edit(this.taro, "{\"displayName\":\"  \",\"bio\":\"\"}"), ErrorCode.DISPLAY_NAME_REQUIRED,
				"displayName");
		assertThat(edit(this.taro, editJson("あ", ""))).hasStatusOk();
		assertThat(edit(this.taro, editJson("あ".repeat(30), "い".repeat(160)))).hasStatusOk();
		assertError(edit(this.taro, editJson("あ".repeat(31), "")), ErrorCode.BAD_REQUEST, "displayName");
		assertError(edit(this.taro, editJson("あ", "い".repeat(161))), ErrorCode.BAD_REQUEST, "bio");
		assertError(edit(this.taro, "{\"displayName\":\"あ\"}"), ErrorCode.BAD_REQUEST, "bio");
	}

	// TC-11-4：カードの名前は、レビュー・コメントから毎回読む
	@Test
	void 表示名を変えるとレビューのカードの名前も変わる() {
		review(this.taro, "感想");
		assertThat(edit(this.taro, editJson("新しい名前", ""))).hasStatusOk();
		assertThat(reviews(this.hanako, "taro_y", null)).bodyJson()
			.extractingPath("$.items[0].author.displayName")
			.isEqualTo("新しい名前");
	}

	// TC-11-6：API は文字のまま返す（文字のまま出すのは画面）
	@Test
	void 自己紹介のタグは文字のまま返す() {
		assertThat(edit(this.taro, editJson("たろう", "<script>alert(1)</script>"))).hasStatusOk();
		assertThat(get(this.hanako, "taro_y")).bodyJson()
			.extractingPath("$.bio")
			.isEqualTo("<script>alert(1)</script>");
	}

	// PF-4：その人のレビューだけを新しい順に 20 件ずつ
	@Test
	void レビューはその人の分を新しい順に二十件ずつ読める() {
		review(this.taro, "ほかの人");
		long oldest = review(this.hanako, "一番古い");
		for (int i = 0; i < 20; i++) {
			review(this.hanako, "感想" + i);
		}
		MvcTestResult first = reviews(this.taro, "hanako", null);
		assertThat(first).hasStatusOk();
		List<Integer> ids = JsonPath.read(body(first), "$.items[*].id");
		assertThat(ids).hasSize(20).isSortedAccordingTo((a, b) -> b - a);
		String next = JsonPath.read(body(first), "$.nextCursor");

		MvcTestResult second = reviews(this.taro, "hanako", next);
		assertThat(second).bodyJson().extractingPath("$.nextCursor").isNull();
		assertThat(JsonPath.<List<Integer>>read(body(second), "$.items[*].id")).containsExactly((int) oldest);
		assertError(reviews(this.taro, "hanako", "x"), ErrorCode.BAD_REQUEST);
	}

	private MvcTestResult get(long userId, String username) {
		return this.mvc.get().uri("/api/users/" + username).with(loggedIn(userId)).exchange();
	}

	private MvcTestResult reviews(long userId, String username, String cursor) {
		String uri = "/api/users/" + username + "/reviews" + ((cursor == null) ? "" : "?cursor=" + cursor);
		return this.mvc.get().uri(uri).with(loggedIn(userId)).exchange();
	}

	private MvcTestResult edit(long userId, String json) {
		return this.mvc.patch()
			.uri("/api/me/profile")
			.with(loggedIn(userId))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.exchange();
	}

	private static String editJson(String displayName, String bio) {
		return "{\"displayName\":\"" + displayName + "\",\"bio\":\"" + bio + "\"}";
	}

	private long review(long userId, String body) {
		return this.jdbc.queryForObject(
				"INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 3, ?) RETURNING id", Long.class,
				userId, book(), body);
	}

	private void follow(long followerId, long followeeId) {
		this.jdbc.update("INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)", followerId, followeeId);
	}

	private void status(long userId, String status) {
		this.jdbc.update("INSERT INTO reading_statuses (user_id, book_id, status) VALUES (?, ?, ?)", userId, book(),
				status);
	}

	private long book() {
		return this.jdbc.queryForObject(
				"INSERT INTO books (google_books_id, title) VALUES (gen_random_uuid()::text, '本') RETURNING id",
				Long.class);
	}

	private long user(String username) {
		return this.jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash) VALUES (?, ?, 'hash') RETURNING id",
				Long.class, username, username + "の表示名");
	}

	private static String body(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
