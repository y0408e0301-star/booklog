package com.example.booklog.shelf;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

// 読書ステータス・本棚（機能定義書 FN-08）を、API を通して（Controller から DB まで）確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの（冊数は G-8 の API（1-11）で確かめる）
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ShelfApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	private long taro;

	private long hanako;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, books, reviews, reading_statuses RESTART IDENTITY CASCADE");
		this.taro = user("taro_y");
		this.hanako = user("hanako");
	}

	// TC-08-1
	@Test
	void 読みたいから読書中から読了と変えても本棚の行は一つ() {
		long book = book("こころ");
		for (String status : new String[] { "WANT", "READING", "READ" }) {
			assertThat(put(this.taro, book, status)).hasStatusOk()
				.bodyJson()
				.isStrictlyEqualTo("{\"status\":\"" + status + "\"}");
			assertThat(myStatus(this.taro, book)).isEqualTo(status);
		}
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM reading_statuses", Long.class)).isOne();
		assertThat(ids(shelf("taro_y", "READ", null))).containsExactly(book);
		assertThat(ids(shelf("taro_y", "WANT", null))).isEmpty();
	}

	// TC-08-2（ST-3）
	@Test
	void 外すと本棚から消えレビューは残る() {
		long book = book("こころ");
		assertThat(this.mvc.post()
			.uri("/api/books/{id}/reviews", book)
			.multipart()
			.param("rating", "4")
			.param("body", "感想")
			.with(loggedIn(this.taro))
			.with(csrf())).hasStatus2xxSuccessful();
		assertThat(myStatus(this.taro, book)).isEqualTo("READ");

		assertThat(this.mvc.delete().uri("/api/books/{id}/status", book).with(loggedIn(this.taro)).with(csrf()))
			.hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"status\":null}");
		assertThat(myStatus(this.taro, book)).isNull();
		assertThat(ids(shelf("taro_y", "READ", null))).isEmpty();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM reviews", Long.class)).isOne();

		// もう一度外しても同じ結果（冪等。FN-08 5 章の 2）
		assertThat(this.mvc.delete().uri("/api/books/{id}/status", book).with(loggedIn(this.taro)).with(csrf()))
			.hasStatusOk();
	}

	// TC-08-3
	@Test
	void 同じステータスを二回送っても並び順は変わらない() {
		long older = book("古い");
		long newer = book("新しい");
		put(this.taro, older, "READ");
		put(this.taro, newer, "READ");
		put(this.taro, older, "READ");
		assertThat(ids(shelf("taro_y", "READ", null))).containsExactly(newer, older);
	}

	// TC-08-5（ST-5）
	@Test
	void 二十一冊は二十冊と一冊で読めステータスを変えた本は先頭に来る() {
		List<Long> books = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			long book = book("本 " + i);
			books.add(book);
			put(this.taro, book, "WANT");
		}
		MvcTestResult first = shelf("taro_y", "WANT", null);
		MvcTestResult second = shelf("taro_y", "WANT", cursor(first));
		assertThat(ids(first)).hasSize(20).isEqualTo(books.reversed().subList(0, 20));
		assertThat(ids(second)).containsExactly(books.getFirst());
		assertThat(cursor(second)).isNull();

		put(this.taro, books.getFirst(), "READING");
		put(this.taro, books.getFirst(), "WANT");
		assertThat(ids(shelf("taro_y", "WANT", null)).getFirst()).isEqualTo(books.getFirst());
	}

	// TC-08-6
	@Test
	void レビューを書いた本には星が出て書いていない本には出ない() {
		long reviewed = book("書いた");
		long notReviewed = book("書いていない");
		this.jdbc.update("INSERT INTO reviews (user_id, book_id, rating, body) VALUES (?, ?, 5, '感想')", this.taro,
				reviewed);
		put(this.taro, reviewed, "READ");
		put(this.taro, notReviewed, "READ");

		String body = body(shelf("taro_y", "READ", null));
		List<Integer> ratings = JsonPath.read(body, "$.items[*].rating");
		assertThat(ids(body)).containsExactly(notReviewed, reviewed);
		assertThat(ratings).containsExactly(null, 5);
	}

	// TC-08-7（ST-7）：ほかの人の本棚は見られるが、変えられるのはログインした人の本棚だけ
	@Test
	void ほかの人の本棚は見られ変えるのは自分の本棚だけ() {
		long book = book("こころ");
		put(this.hanako, book, "READING");
		assertThat(ids(shelf("hanako", "READING", null))).containsExactly(book);

		put(this.taro, book, "WANT");
		assertThat(myStatus(this.hanako, book)).isEqualTo("READING");
		assertThat(myStatus(this.taro, book)).isEqualTo("WANT");
	}

	@Test
	void ない本は404でいないユーザーは404() {
		assertError(put(this.taro, 999, "READ"), ErrorCode.BOOK_NOT_FOUND);
		assertError(this.mvc.delete().uri("/api/books/999/status").with(loggedIn(this.taro)).with(csrf()).exchange(),
				ErrorCode.BOOK_NOT_FOUND);
		assertError(shelf("nobody", "READ", null), ErrorCode.USER_NOT_FOUND);
	}

	@Test
	void ステータスが違えば400() {
		long book = book("こころ");
		assertError(put(this.taro, book, "DONE"), ErrorCode.BAD_REQUEST, "status");
		assertError(shelf("taro_y", "DONE", null), ErrorCode.BAD_REQUEST, "status");
		assertError(shelf("taro_y", "READ", "x"), ErrorCode.BAD_REQUEST, "cursor");
	}

	private MvcTestResult put(long userId, long bookId, String status) {
		return this.mvc.put()
			.uri("/api/books/{id}/status", bookId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"" + status + "\"}")
			.with(loggedIn(userId))
			.with(csrf())
			.exchange();
	}

	private MvcTestResult shelf(String username, String status, String cursor) {
		String uri = "/api/users/" + username + "/bookshelf?status=" + status
				+ ((cursor == null) ? "" : "&cursor=" + cursor);
		return this.mvc.get().uri(uri).with(loggedIn(this.taro)).exchange();
	}

	// 本の詳細（GET /api/books/{id}）の myStatus
	private String myStatus(long userId, long bookId) {
		return JsonPath.read(body(this.mvc.get().uri("/api/books/{id}", bookId).with(loggedIn(userId)).exchange()),
				"$.myStatus");
	}

	private long book(String title) {
		return this.jdbc.queryForObject(
				"INSERT INTO books (google_books_id, title) VALUES (gen_random_uuid()::text, ?) RETURNING id",
				Long.class, title);
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
		return ids(body(result));
	}

	private static List<Long> ids(String body) {
		List<Number> ids = JsonPath.read(body, "$.items[*].book.id");
		return ids.stream().map(Number::longValue).toList();
	}

	private static String cursor(MvcTestResult result) {
		return JsonPath.read(body(result), "$.nextCursor");
	}

}
