package com.example.booklog.comment;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.jayway.jsonpath.JsonPath;

// コメント（機能定義書 FN-06）を、API を通して（Controller から DB まで）確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class CommentApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	private long taro;

	private long hanako;

	private long review;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, comments RESTART IDENTITY CASCADE");
		this.taro = user("taro_y");
		this.hanako = user("hanako");
		long book = this.jdbc
			.queryForObject("INSERT INTO books (google_books_id, title) VALUES ('g', 'こころ') RETURNING id", Long.class);
		this.review = this.jdbc.queryForObject(
				"INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 4, '感想') RETURNING id", Long.class,
				this.taro, book);
	}

	// C-1・TC-06-4：付けると一覧に出て、レビューのコメント数が合う。自分のレビューにも付けられる
	@Test
	void 付けると一覧に出てコメント数が増える() {
		MvcTestResult created = create(this.hanako, "私も泣きました");
		assertThat(created).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.mine").isEqualTo(true);
		assertThat(create(this.taro, "ありがとう")).hasStatus(HttpStatus.CREATED);

		assertThat(bodies(list(this.taro, null))).containsExactly("私も泣きました", "ありがとう");
		assertThat(list(this.taro, null)).bodyJson().extractingPath("$.items[0].mine").isEqualTo(false);
		assertThat(this.mvc.get().uri("/api/reviews/{id}", this.review).with(loggedIn(this.hanako))).bodyJson()
			.extractingPath("$.commentCount")
			.isEqualTo(2);
		assertThat(this.mvc.get().uri("/api/timeline?tab=all").with(loggedIn(this.hanako))).bodyJson()
			.extractingPath("$.items[0].commentCount")
			.isEqualTo(2);

		// 消すと減る
		long id = ((Number) JsonPath.read(body(created), "$.id")).longValue();
		assertThat(delete(this.hanako, id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(this.mvc.get().uri("/api/reviews/{id}", this.review).with(loggedIn(this.hanako))).bodyJson()
			.extractingPath("$.commentCount")
			.isEqualTo(1);
	}

	// TC-06-2
	@Test
	void 五十一件は五十件と一件で古い順に読める() {
		for (int i = 1; i <= 51; i++) {
			this.jdbc.update("INSERT INTO comments (review_id, user_id, body) VALUES (?, ?, ?)", this.review,
					this.hanako, "コメント " + i);
		}
		MvcTestResult first = list(this.taro, null);
		String cursor = JsonPath.read(body(first), "$.nextCursor");
		MvcTestResult second = list(this.taro, cursor);
		assertThat(bodies(first)).hasSize(50).startsWith("コメント 1").endsWith("コメント 50");
		assertThat(bodies(second)).containsExactly("コメント 51");
		assertThat(second).bodyJson().extractingPath("$.nextCursor").isNull();
	}

	// TC-06-1
	@Test
	void 本文は1から500文字() {
		assertError(create(this.hanako, " \n "), ErrorCode.COMMENT_REQUIRED, "body");
		assertThat(create(this.hanako, "あ")).hasStatus(HttpStatus.CREATED);
		assertThat(create(this.hanako, "あ".repeat(500))).hasStatus(HttpStatus.CREATED);
		assertError(create(this.hanako, "あ".repeat(501)), ErrorCode.BAD_REQUEST, "body");
	}

	// TC-06-3：レビューを書いた人でも、ほかの人のコメントは消せない
	@Test
	void ほかの人のコメントは消せない() {
		long id = ((Number) JsonPath.read(body(create(this.hanako, "a")), "$.id")).longValue();
		assertError(delete(this.taro, id), ErrorCode.FORBIDDEN);
		assertThat(bodies(list(this.taro, null))).containsExactly("a");
		assertThat(delete(this.hanako, id)).hasStatus(HttpStatus.NO_CONTENT);
		assertError(delete(this.hanako, id), ErrorCode.COMMENT_NOT_FOUND);
	}

	// TC-06-6：HTML の記号もそのまま保存して返す（画面は文字として出す）
	@Test
	void HTMLの記号はそのまま() {
		create(this.hanako, "<b>太字</b>");
		assertThat(bodies(list(this.taro, null))).containsExactly("<b>太字</b>");
	}

	// TC-06-7
	@Test
	void レビューを削除するとコメントも消え一覧は404() {
		create(this.hanako, "a");
		assertThat(this.mvc.delete().uri("/api/reviews/{id}", this.review).with(loggedIn(this.taro)).with(csrf()))
			.hasStatus2xxSuccessful();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM comments", Long.class)).isZero();
		assertError(list(this.taro, null), ErrorCode.REVIEW_NOT_FOUND);
		assertError(create(this.hanako, "b"), ErrorCode.REVIEW_NOT_FOUND);
	}

	private MvcTestResult create(long userId, String body) {
		return this.mvc.post()
			.uri("/api/reviews/{id}/comments", this.review)
			.contentType(MediaType.APPLICATION_JSON)
			// テストの本文には「"」「\」を使わないので、改行だけを JSON の書き方にする
			.content("{\"body\":\"" + body.replace("\n", "\\n") + "\"}")
			.with(loggedIn(userId))
			.with(csrf())
			.exchange();
	}

	private MvcTestResult delete(long userId, long commentId) {
		return this.mvc.delete().uri("/api/comments/{id}", commentId).with(loggedIn(userId)).with(csrf()).exchange();
	}

	private MvcTestResult list(long userId, String cursor) {
		String uri = "/api/reviews/" + this.review + "/comments" + ((cursor == null) ? "" : "?cursor=" + cursor);
		return this.mvc.get().uri(uri).with(loggedIn(userId)).exchange();
	}

	private long user(String username) {
		return this.jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash) VALUES (?, ?, 'hash') RETURNING id",
				Long.class, username, username + "の表示名");
	}

	private static String body(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private static List<String> bodies(MvcTestResult result) {
		return JsonPath.read(body(result), "$.items[*].body");
	}

}
