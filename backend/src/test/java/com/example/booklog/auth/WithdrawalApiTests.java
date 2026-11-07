package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.MinIOContainer;

import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.storage.PhotoStorage;
import com.jayway.jsonpath.JsonPath;

import jakarta.servlet.http.Cookie;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

// 退会（機能定義書 FN-01 A-10〜A-13）を、API とテスト用の S3（MinIO）を通して確かめる。TC-01-13〜TC-01-16
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class WithdrawalApiTests {

	private static final String PASSWORD = "password1";

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3 };

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MinIOContainer minio;

	@MockitoSpyBean
	PhotoStorage storage;

	private S3Client s3;

	private long book;

	private MvcTestResult taro;

	private MvcTestResult hanako;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("""
				TRUNCATE users, refresh_tokens, books, reviews, reading_statuses, follows, storage_deletions
				RESTART IDENTITY CASCADE
				""");
		this.s3 = TestcontainersConfiguration.s3Client(this.minio);
		emptyBucket();
		this.book = this.jdbc
			.queryForObject("INSERT INTO books (google_books_id, title) VALUES ('a', 'こころ') RETURNING id", Long.class);
		this.taro = signup("taro_y");
		this.hanako = signup("hanako");
	}

	@AfterEach
	void tearDown() {
		this.s3.close();
	}

	// TC-01-13：パスワードが違うと何も消えない
	@Test
	void パスワードが違えば何も消えない() {
		long review = postReview(this.taro);

		MvcTestResult result = withdraw(this.taro, "wrong-password1");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("PASSWORD_WRONG");
		assertThat(result).bodyJson().extractingPath("$.field").isEqualTo("password");
		assertThat(count("users")).isEqualTo(2);
		assertThat(count("reviews")).isOne();
		assertThat(bucketKeys()).hasSize(1);
		assertThat(getReview(this.taro, review)).hasStatusOk();
	}

	// TC-01-13・TC-01-14：その人のものはすべて消え、本とほかの人のものは残る
	// A-12・BR-14・N-10
	@Test
	void 退会するとその人のデータと写真が消え本は残る() {
		long mine = postReview(this.taro);
		long theirs = postReview(this.hanako);
		String theirKey = this.jdbc.queryForObject("SELECT storage_key FROM review_photos WHERE review_id = ?",
				String.class, theirs);
		// 自分のレビューに付いたほかの人のコメント・参考になった、ほかの人のレビューに付けた自分の分
		comment(this.hanako, mine);
		comment(this.taro, theirs);
		helpful(this.hanako, mine);
		helpful(this.taro, theirs);
		follow(this.taro, "hanako");
		follow(this.hanako, "taro_y");

		assertThat(withdraw(this.taro, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(this.jdbc.queryForList("SELECT username FROM users", String.class)).containsExactly("hanako");
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM refresh_tokens WHERE user_id = 1", Long.class))
			.isZero();
		assertThat(this.jdbc.queryForList("SELECT id FROM reviews", Long.class)).containsExactly(theirs);
		assertThat(count("review_photos")).isOne();
		assertThat(count("comments")).isZero();
		assertThat(count("helpfuls")).isZero();
		assertThat(count("follows")).isZero();
		// hanako がレビューを書いたので読了の 1 行だけが残る
		assertThat(count("reading_statuses")).isOne();
		assertThat(count("books")).isOne();
		assertThat(bucketKeys()).containsExactly(theirKey);
		assertThat(count("storage_deletions")).isZero();
		// ほかの人の件数も減る（TC-01-14）
		assertThat(getReview(this.hanako, theirs)).bodyJson().extractingPath("$.helpfulCount").isEqualTo(0);
		assertThat(getReview(this.hanako, theirs)).bodyJson().extractingPath("$.commentCount").isEqualTo(0);
		assertThat(this.mvc.get().uri("/api/users/hanako").cookie(cookies(this.hanako))).bodyJson()
			.extractingPath("$.followerCount")
			.isEqualTo(0);
	}

	// TC-01-15：別のブラウザに残っていた JWT・リフレッシュトークンでは 401。同じユーザー ID でまた登録できる
	@Test
	void 退会した後は残っていたトークンでは401で同じユーザーIDで登録できる() {
		Cookie access = this.taro.getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
		Cookie refresh = this.taro.getResponse().getCookie(AuthCookies.REFRESH_TOKEN);

		assertThat(withdraw(this.taro, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

		for (String uri : List.of("/api/auth/me", "/api/timeline?tab=all", "/api/users/hanako")) {
			MvcTestResult result = this.mvc.get().uri(uri).cookie(access).exchange();
			assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
			assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("UNAUTHENTICATED");
		}
		assertThat(this.mvc.post().uri("/api/auth/refresh").with(csrf()).cookie(refresh))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(signup("taro_y")).hasStatus(HttpStatus.CREATED);
	}

	// TC-01-16
	@Test
	void パスワードを五回間違えてもログインできなくならない() {
		for (int i = 0; i < 5; i++) {
			assertThat(withdraw(this.taro, "wrong-password1")).hasStatus(HttpStatus.BAD_REQUEST);
		}
		assertThat(this.mvc.post()
			.uri("/api/auth/login")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"taro_y\",\"password\":\"" + PASSWORD + "\"}")).hasStatusOk();
	}

	// BR-5：S3 から消せなくても退会はでき、消せなかった写真を記録する
	@Test
	void S3から消せなかった写真は記録する() {
		postReview(this.taro);
		String key = this.jdbc.queryForObject("SELECT storage_key FROM review_photos", String.class);
		Mockito.doThrow(SdkClientException.create("S3 につながらない")).when(this.storage).delete(key);

		assertThat(withdraw(this.taro, PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(count("reviews")).isZero();
		assertThat(this.jdbc.queryForList("SELECT storage_key FROM storage_deletions", String.class))
			.containsExactly(key);
	}

	// データベースで失敗したら何も消さない（9 章）。S3 の写真も消さない
	@Test
	void データベースで失敗したら何も消えない() {
		postReview(this.taro);
		this.jdbc.execute("""
				CREATE FUNCTION fail_users() RETURNS trigger AS $$
				BEGIN RAISE EXCEPTION 'テストで失敗させる'; END; $$ LANGUAGE plpgsql
				""");
		this.jdbc
			.execute("CREATE TRIGGER fail_users BEFORE DELETE ON users FOR EACH ROW EXECUTE FUNCTION fail_users()");
		try {
			assertThat(withdraw(this.taro, PASSWORD)).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		}
		finally {
			this.jdbc.execute("DROP TRIGGER fail_users ON users");
			this.jdbc.execute("DROP FUNCTION fail_users()");
		}
		assertThat(count("users")).isEqualTo(2);
		assertThat(count("reviews")).isOne();
		assertThat(bucketKeys()).hasSize(1);
	}

	private MvcTestResult signup(String username) {
		return this.mvc.post()
			.uri("/api/auth/signup")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"" + username + "\",\"displayName\":\"" + username + "\",\"password\":\""
					+ PASSWORD + "\"}")
			.exchange();
	}

	private MvcTestResult withdraw(MvcTestResult user, String password) {
		return this.mvc.delete()
			.uri("/api/me")
			.with(csrf())
			.cookie(cookies(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"" + password + "\"}")
			.exchange();
	}

	private long postReview(MvcTestResult user) {
		MvcTestResult result = this.mvc.perform(MockMvcRequestBuilders.multipart("/api/books/{id}/reviews", this.book)
			.file(new MockMultipartFile("photos", "a.png", "image/png", PNG))
			.param("rating", "4")
			.param("body", "感想")
			.cookie(cookies(user))
			.with(csrf()));
		assertThat(result).hasStatus(HttpStatus.CREATED);
		return JsonPath
			.<Number>read(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8), "$.id")
			.longValue();
	}

	private MvcTestResult getReview(MvcTestResult user, long id) {
		return this.mvc.get().uri("/api/reviews/{id}", id).cookie(cookies(user)).exchange();
	}

	private void comment(MvcTestResult user, long reviewId) {
		assertThat(this.mvc.post()
			.uri("/api/reviews/{id}/comments", reviewId)
			.with(csrf())
			.cookie(cookies(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"コメント\"}")).hasStatus(HttpStatus.CREATED);
	}

	private void helpful(MvcTestResult user, long reviewId) {
		assertThat(this.mvc.put().uri("/api/reviews/{id}/helpful", reviewId).with(csrf()).cookie(cookies(user)))
			.hasStatusOk();
	}

	private void follow(MvcTestResult user, String username) {
		assertThat(this.mvc.put().uri("/api/users/{name}/follow", username).with(csrf()).cookie(cookies(user)))
			.hasStatusOk();
	}

	private static Cookie cookies(MvcTestResult user) {
		return user.getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
	}

	private long count(String table) {
		return this.jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	private List<String> bucketKeys() {
		return this.s3.listObjectsV2((request) -> request.bucket(TestcontainersConfiguration.BUCKET))
			.contents()
			.stream()
			.map(S3Object::key)
			.toList();
	}

	private void emptyBucket() {
		bucketKeys().forEach((key) -> this.s3
			.deleteObject((request) -> request.bucket(TestcontainersConfiguration.BUCKET).key(key)));
	}

}
