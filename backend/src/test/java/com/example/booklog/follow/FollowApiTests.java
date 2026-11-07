package com.example.booklog.follow;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
import com.jayway.jsonpath.JsonPath;

// フォロー（機能定義書 FN-07）を、API を通して（Controller から DB まで）確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの（G-8 のフォロワー数は 1-11 で確かめる）
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class FollowApiTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	private long taro;

	private long hanako;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, follows RESTART IDENTITY CASCADE");
		this.taro = user("taro_y", "");
		this.hanako = user("hanako", "ミステリーをよく読みます\n二行目");
	}

	// TC-07-1：フォローすると相手のフォロワーの一覧に出て、数が返る。解除で元に戻る
	@Test
	void フォローすると一覧に出て解除で戻る() {
		assertThat(follow(this.taro, "hanako")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"following":true,"followerCount":1}
				""");
		assertThat(usernames(list(this.taro, "hanako", "followers", null))).containsExactly("taro_y");
		assertThat(list(this.taro, "taro_y", "following", null)).bodyJson().isStrictlyEqualTo("""
				{"items":[{"username":"hanako","displayName":"hanakoの表示名","bioFirstLine":"ミステリーをよく読みます",
				  "followedByMe":true,"me":false}],"nextCursor":null}
				""");
		// 自分の行は me が true
		assertThat(list(this.hanako, "hanako", "followers", null)).bodyJson()
			.extractingPath("$.items[0].me")
			.isEqualTo(false);
		assertThat(list(this.taro, "hanako", "followers", null)).bodyJson()
			.extractingPath("$.items[0].me")
			.isEqualTo(true);

		assertThat(unfollow(this.taro, "hanako")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"following":false,"followerCount":0}
				""");
		assertThat(usernames(list(this.taro, "hanako", "followers", null))).isEmpty();
	}

	// TC-07-2・5 章の 2：何回送っても同じ結果。同時に送っても行は 1 つ
	// BR-6
	@Test
	void 何回送っても同時に送っても行は0か1() throws Exception {
		Callable<MvcTestResult> task = () -> follow(this.taro, "hanako");
		try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
			List<Future<MvcTestResult>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				results.add(executor.submit(task));
			}
			for (Future<MvcTestResult> result : results) {
				assertThat(result.get()).hasStatusOk();
			}
		}
		assertThat(rows()).isOne();
		unfollow(this.taro, "hanako");
		unfollow(this.taro, "hanako");
		assertThat(rows()).isZero();
	}

	// TC-07-3
	@Test
	void 自分には400() {
		assertError(follow(this.taro, "taro_y"), ErrorCode.CANNOT_FOLLOW_SELF);
		assertThat(rows()).isZero();
	}

	// TC-07-4：21 人は 20 人と 1 人で読め、新しい順に並ぶ
	@Test
	void 二十一人は二十人と一人で新しい順に読める() {
		List<String> followed = new ArrayList<>();
		for (int i = 0; i < 21; i++) {
			String name = "user" + i;
			user(name, "");
			follow(this.taro, name);
			followed.add(name);
		}
		MvcTestResult first = list(this.hanako, "taro_y", "following", null);
		String cursor = JsonPath.read(body(first), "$.nextCursor");
		MvcTestResult second = list(this.hanako, "taro_y", "following", cursor);
		assertThat(usernames(first)).isEqualTo(followed.reversed().subList(0, 20));
		assertThat(usernames(second)).containsExactly("user0");
		assertThat(second).bodyJson().extractingPath("$.nextCursor").isNull();
	}

	// TC-07-5・F-7
	@Test
	void いないユーザーは404() {
		assertError(list(this.taro, "nobody", "following", null), ErrorCode.USER_NOT_FOUND);
		assertError(list(this.taro, "nobody", "followers", null), ErrorCode.USER_NOT_FOUND);
		assertError(follow(this.taro, "nobody"), ErrorCode.USER_NOT_FOUND);
	}

	// ユーザー ID は大文字・小文字を区別しない（A-2）
	@Test
	void ユーザーIDの大文字小文字は区別しない() {
		assertThat(follow(this.taro, "HANAKO")).hasStatusOk();
		assertThat(usernames(list(this.taro, "Taro_Y", "following", null))).containsExactly("hanako");
	}

	private MvcTestResult follow(long userId, String username) {
		return this.mvc.put().uri("/api/users/{u}/follow", username).with(loggedIn(userId)).with(csrf()).exchange();
	}

	private MvcTestResult unfollow(long userId, String username) {
		return this.mvc.delete().uri("/api/users/{u}/follow", username).with(loggedIn(userId)).with(csrf()).exchange();
	}

	private MvcTestResult list(long userId, String username, String kind, String cursor) {
		String uri = "/api/users/" + username + "/" + kind + ((cursor == null) ? "" : "?cursor=" + cursor);
		return this.mvc.get().uri(uri).with(loggedIn(userId)).exchange();
	}

	private long rows() {
		return this.jdbc.queryForObject("SELECT count(*) FROM follows", Long.class);
	}

	private long user(String username, String bio) {
		return this.jdbc.queryForObject(
				"INSERT INTO users (username, display_name, password_hash, bio) VALUES (?, ?, 'hash', ?) RETURNING id",
				Long.class, username, username + "の表示名", bio);
	}

	private static String body(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	private static List<String> usernames(MvcTestResult result) {
		return JsonPath.read(body(result), "$.items[*].username");
	}

}
