package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

// ユーザー登録・ログインの API（機能定義書 FN-01）を、API を通して確かめる。10 章の「テストの観点」のうち、
// サーバー側で確かめられるもの。DB はテスト用に Docker で起動する（TestcontainersConfiguration）。
// ログインの失敗の回数は 401 を返してもコミットするため、トランザクションの巻き戻しではなく、テストごとに表を空にする
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class AuthApiTests {

	private static final String PASSWORD = "password1";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RefreshTokenCleanup cleanup;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, refresh_tokens RESTART IDENTITY CASCADE");
		this.clock.reset();
	}

	// ---- 登録（A-1〜A-4）----

	// TC-01-8
	@Test
	void 登録するとログインした状態になり自分の情報が返る() {
		MvcTestResult result = signup("Taro_Y", "たろう", PASSWORD);
		assertThat(result).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
				{"username":"Taro_Y","displayName":"たろう"}
				""");

		MockHttpServletResponse response = result.getResponse();
		Cookie access = response.getCookie(AuthCookies.ACCESS_TOKEN);
		Cookie refresh = response.getCookie(AuthCookies.REFRESH_TOKEN);
		assertThat(access).isNotNull();
		assertThat(refresh).isNotNull();
		// 画面の JavaScript から読めないこと、リフレッシュトークンは取り直しとログアウトのときだけ送られること
		assertThat(access.isHttpOnly()).isTrue();
		assertThat(access.getPath()).isEqualTo("/api");
		assertThat(access.getMaxAge()).isEqualTo(15 * 60);
		assertThat(refresh.isHttpOnly()).isTrue();
		assertThat(refresh.getPath()).isEqualTo("/api/auth");
		assertThat(refresh.getMaxAge()).isEqualTo(14 * 24 * 60 * 60);
		assertThat(response.getHeaders("Set-Cookie")).filteredOn((header) -> !header.startsWith("XSRF-TOKEN="))
			.hasSize(2)
			.allMatch((header) -> header.contains("SameSite=Lax"));

		assertThat(this.mvc.get().uri("/api/auth/me").cookie(access)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.username")
			.isEqualTo("Taro_Y");
	}

	// TC-01-1
	@Test
	void ユーザーIDは3から15文字の半角英数字とアンダースコアだけ() {
		assertError(signup("ab", "名前", PASSWORD), HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username");
		assertThat(signup("abc", "名前", PASSWORD)).hasStatus(HttpStatus.CREATED);
		assertThat(signup("a23456789012345", "名前", PASSWORD)).hasStatus(HttpStatus.CREATED);
		assertError(signup("a234567890123456", "名前", PASSWORD), HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username");
		assertError(signup("ｔａｒｏ", "名前", PASSWORD), HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username");
		assertError(signup("taro-y", "名前", PASSWORD), HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username");
		assertError(signup(null, "名前", PASSWORD), HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username");
	}

	// BR-1
	@Test
	void ユーザーIDは前後の空白を取り除いて保存する() {
		assertThat(signup("  hanako  ", "はなこ", PASSWORD)).bodyJson().extractingPath("$.username").isEqualTo("hanako");
	}

	// TC-01-1
	@Test
	void 大文字と小文字だけが違うユーザーIDは使えない() {
		assertThat(signup("Taro", "たろう", PASSWORD)).hasStatus(HttpStatus.CREATED);
		assertError(signup("taro", "たろう 2", PASSWORD), HttpStatus.CONFLICT, "USERNAME_TAKEN", "username");

		assertThat(this.mvc.get().uri("/api/auth/username-availability?username=TARO")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"available\":false}");
		assertThat(this.mvc.get().uri("/api/auth/username-availability?username=jiro")).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"available\":true}");
		assertError(this.mvc.get().uri("/api/auth/username-availability?username=ab").exchange(),
				HttpStatus.BAD_REQUEST, "USERNAME_INVALID", "username");
	}

	// TC-01-2
	@Test
	void パスワードは英字と数字を含む8から64文字の半角文字() {
		assertError(signup("user1", "名前", "passwo1"), HttpStatus.BAD_REQUEST, "PASSWORD_INVALID", "password");
		assertThat(signup("user2", "名前", "passwo12")).hasStatus(HttpStatus.CREATED);
		assertThat(signup("user3", "名前", "a1".repeat(32))).hasStatus(HttpStatus.CREATED);
		assertError(signup("user4", "名前", "a1".repeat(32) + "b"), HttpStatus.BAD_REQUEST, "PASSWORD_INVALID",
				"password");
		assertError(signup("user5", "名前", "password"), HttpStatus.BAD_REQUEST, "PASSWORD_INVALID", "password");
		assertError(signup("user6", "名前", "12345678"), HttpStatus.BAD_REQUEST, "PASSWORD_INVALID", "password");
		assertError(signup("user7", "名前", "ぱすわーど1234a"), HttpStatus.BAD_REQUEST, "PASSWORD_INVALID", "password");
	}

	// BR-1
	// TC-01-2
	@Test
	void パスワードの前後の空白は取り除かずにそのまま使う() {
		assertThat(signup("spacer", "名前", " pass1234 ")).hasStatus(HttpStatus.CREATED);
		assertError(login("spacer", "pass1234"), HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null);
		assertThat(login("spacer", " pass1234 ")).hasStatusOk();
	}

	@Test
	void 表示名は前後の空白を取り除いて1から30文字() {
		assertError(signup("user1", "   ", PASSWORD), HttpStatus.BAD_REQUEST, "DISPLAY_NAME_REQUIRED", "displayName");
		// 絵文字（サロゲートペア）も 1 文字と数える（要件定義書 5 章：コードポイント単位）
		assertThat(signup("user2", " " + "😀".repeat(30) + " ", PASSWORD)).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.extractingPath("$.displayName")
			.isEqualTo("😀".repeat(30));
		assertError(signup("user3", "あ".repeat(31), PASSWORD), HttpStatus.BAD_REQUEST, "BAD_REQUEST", "displayName");
	}

	@Test
	void JSONが読めないときは400を機能定義書のエラーの形で返す() {
		assertError(this.mvc.post()
			.uri("/api/auth/signup")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":")
			.exchange(), HttpStatus.BAD_REQUEST, "BAD_REQUEST", null);
	}

	// ---- ログイン（A-5〜A-7）----

	@Test
	void ユーザーIDの大文字と小文字を区別せずにログインできる() {
		signup("Taro", "たろう", PASSWORD);
		MvcTestResult result = login("TARO", PASSWORD);
		assertThat(result).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("Taro");
		assertThat(result.getResponse().getCookie(AuthCookies.ACCESS_TOKEN)).isNotNull();
		assertThat(result.getResponse().getCookie(AuthCookies.REFRESH_TOKEN)).isNotNull();
	}

	// TC-01-4
	@Test
	void いないユーザーIDと違うパスワードで同じ応答を返す() throws Exception {
		signup("taro", "たろう", PASSWORD);
		MvcTestResult wrongPassword = login("taro", "wrong1234");
		MvcTestResult noUser = login("nobody", PASSWORD);
		assertError(wrongPassword, HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null);
		assertError(noUser, HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null);
		assertThat(wrongPassword.getResponse().getContentAsString(StandardCharsets.UTF_8))
			.isEqualTo(noUser.getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertError(login("", ""), HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null);
	}

	// BR-8
	// TC-01-3
	@Test
	void ログインに成功すると失敗の回数が0に戻る() {
		signup("taro", "たろう", PASSWORD);
		failLogin("taro", 4);
		assertThat(login("taro", PASSWORD)).hasStatusOk();
		// 0 に戻っているので、また 4 回失敗してもロックされない
		failLogin("taro", 4);
		assertThat(login("taro", PASSWORD)).hasStatusOk();
	}

	// BR-8
	// TC-01-3
	@Test
	void 五回続けて失敗すると15分間ログインできない() {
		signup("taro", "たろう", PASSWORD);
		failLogin("taro", 4);
		assertError(login("taro", "wrong1234"), HttpStatus.LOCKED, "LOGIN_LOCKED", null);
		// 正しいパスワードでもログインできない。ユーザー ID の大文字・小文字を変えても同じ
		assertError(login("TARO", PASSWORD), HttpStatus.LOCKED, "LOGIN_LOCKED", null);

		this.clock.advance(Duration.ofMinutes(14));
		assertError(login("taro", PASSWORD), HttpStatus.LOCKED, "LOGIN_LOCKED", null);
		this.clock.advance(Duration.ofMinutes(1));
		assertThat(login("taro", PASSWORD)).hasStatusOk();
	}

	@Test
	void 登録できない長さの全角のパスワードでログインしても失敗として数える() {
		signup("taro", "たろう", PASSWORD);
		assertError(login("taro", "あ".repeat(30)), HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null);
		assertThat(this.jdbc.queryForObject("SELECT failed_login_count FROM users", Integer.class)).isEqualTo(1);
	}

	// ---- ログインの確かめ方（機能定義書 3.2）----

	@Test
	void ログインしていないとAPIは401を返す() {
		assertError(this.mvc.get().uri("/api/auth/me").exchange(), HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
	}

	// TC-01-7
	@Test
	void CSRFトークンがないPOSTは403を返す() {
		Cookie access = signup("taro", "たろう", PASSWORD).getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
		assertError(this.mvc.post().uri("/api/auth/logout").cookie(access).exchange(), HttpStatus.FORBIDDEN,
				"FORBIDDEN", null);
		assertError(this.mvc.post()
			.uri("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"taro\",\"password\":\"" + PASSWORD + "\"}")
			.exchange(), HttpStatus.FORBIDDEN, "FORBIDDEN", null);
	}

	// TC-01-9
	@Test
	void 期限切れのJWTはTOKEN_EXPIREDを返す() {
		Cookie access = signup("taro", "たろう", PASSWORD).getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
		this.clock.advance(Duration.ofMinutes(14));
		assertThat(this.mvc.get().uri("/api/auth/me").cookie(access)).hasStatusOk();
		this.clock.advance(Duration.ofMinutes(1));
		assertError(this.mvc.get().uri("/api/auth/me").cookie(access).exchange(), HttpStatus.UNAUTHORIZED,
				"TOKEN_EXPIRED", null);
	}

	@Test
	void 期限切れのJWTのCookieが残っていてもログインできる() {
		Cookie access = signup("taro", "たろう", PASSWORD).getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
		this.clock.advance(Duration.ofHours(1));
		assertThat(this.mvc.post()
			.uri("/api/auth/login")
			.with(csrf())
			.cookie(access)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"taro\",\"password\":\"" + PASSWORD + "\"}")).hasStatusOk();
	}

	// TC-01-9
	@Test
	void 書き換えたJWTと別の秘密の値で署名したJWTは401を返す() {
		String token = signup("taro", "たろう", PASSWORD).getResponse().getCookie(AuthCookies.ACCESS_TOKEN).getValue();

		// 中身（sub）を別の人に書き換える。署名が合わなくなる
		String[] parts = token.split("\\.");
		String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
			.replace("\"sub\":\"1\"", "\"sub\":\"2\"");
		String tampered = parts[0] + "."
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "."
				+ parts[2];
		assertError(this.mvc.get().uri("/api/auth/me").cookie(accessCookie(tampered)).exchange(),
				HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);

		assertError(this.mvc.get().uri("/api/auth/me").cookie(accessCookie(signedWithOtherSecret())).exchange(),
				HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", null);
	}

	// ---- 取り直し（FN-01 3.3、BR-9）----

	// A-8
	@Test
	void 取り直すと両方のトークンが新しくなる() {
		MockHttpServletResponse signup = signup("taro", "たろう", PASSWORD).getResponse();
		this.clock.advance(Duration.ofMinutes(20));

		MvcTestResult refreshed = refresh(signup.getCookie(AuthCookies.REFRESH_TOKEN));
		assertThat(refreshed).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("taro");
		Cookie newAccess = refreshed.getResponse().getCookie(AuthCookies.ACCESS_TOKEN);
		Cookie newRefresh = refreshed.getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
		assertThat(newRefresh.getValue()).isNotEqualTo(signup.getCookie(AuthCookies.REFRESH_TOKEN).getValue());
		assertThat(this.mvc.get().uri("/api/auth/me").cookie(newAccess)).hasStatusOk();
	}

	// TC-01-11
	@Test
	void 替わる前のリフレッシュトークンが使われたらその人のログインをすべて切る() {
		signup("taro", "たろう", PASSWORD);
		Cookie otherBrowser = login("taro", PASSWORD).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
		Cookie old = login("taro", PASSWORD).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
		Cookie rotated = refresh(old).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);

		MvcTestResult reused = refresh(old);
		assertError(reused, HttpStatus.UNAUTHORIZED, "REFRESH_FAILED", null);
		// 使えなくなった Cookie は消す
		assertThat(reused.getResponse().getCookie(AuthCookies.REFRESH_TOKEN).getMaxAge()).isZero();

		assertError(refresh(rotated), HttpStatus.UNAUTHORIZED, "REFRESH_FAILED", null);
		assertError(refresh(otherBrowser), HttpStatus.UNAUTHORIZED, "REFRESH_FAILED", null);
	}

	// TC-01-13
	@Test
	void 最後の操作から14日を過ぎるとログインが切れる() {
		Cookie refresh = signup("taro", "たろう", PASSWORD).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
		this.clock.advance(Duration.ofDays(13));
		refresh = refresh(refresh).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);
		// 取り直したので、最初のログインから 14 日を過ぎてもまだ使える
		this.clock.advance(Duration.ofDays(13));
		refresh = refresh(refresh).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);

		this.clock.advance(Duration.ofDays(14).plusMinutes(1));
		assertError(refresh(refresh), HttpStatus.UNAUTHORIZED, "REFRESH_FAILED", null);
	}

	@Test
	void リフレッシュトークンがないか知らないものなら401を返す() {
		assertError(this.mvc.post().uri("/api/auth/refresh").with(csrf()).exchange(), HttpStatus.UNAUTHORIZED,
				"REFRESH_FAILED", null);
		assertError(refresh(new Cookie(AuthCookies.REFRESH_TOKEN, "unknown")), HttpStatus.UNAUTHORIZED,
				"REFRESH_FAILED", null);
	}

	// ---- ログアウト（A-9）----

	// N-19
	// TC-01-12
	@Test
	void ログアウトするとそのブラウザのリフレッシュトークンだけが使えなくなる() {
		signup("taro", "たろう", PASSWORD);
		MockHttpServletResponse browserA = login("taro", PASSWORD).getResponse();
		Cookie browserB = login("taro", PASSWORD).getResponse().getCookie(AuthCookies.REFRESH_TOKEN);

		MvcTestResult logout = this.mvc.post()
			.uri("/api/auth/logout")
			.with(csrf())
			.cookie(browserA.getCookie(AuthCookies.ACCESS_TOKEN), browserA.getCookie(AuthCookies.REFRESH_TOKEN))
			.exchange();
		assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(logout.getResponse().getCookie(AuthCookies.ACCESS_TOKEN).getMaxAge()).isZero();
		assertThat(logout.getResponse().getCookie(AuthCookies.REFRESH_TOKEN).getMaxAge()).isZero();

		assertThat(refresh(browserB)).hasStatusOk();
		// ログアウトしたトークンが使われたら、盗まれたとみなして、ほかのブラウザのログインも切る（BR-9）
		assertError(refresh(browserA.getCookie(AuthCookies.REFRESH_TOKEN)), HttpStatus.UNAUTHORIZED, "REFRESH_FAILED",
				null);
	}

	@Test
	void 期限切れのリフレッシュトークンをまとめて消す() {
		signup("taro", "たろう", PASSWORD);
		this.clock.advance(Duration.ofDays(10));
		login("taro", PASSWORD);
		this.clock.advance(Duration.ofDays(5));

		this.cleanup.deleteExpired();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM refresh_tokens", Integer.class)).isEqualTo(1);
	}

	// ---- 補助 ----

	private MvcTestResult signup(String username, String displayName, String password) {
		return this.mvc.post()
			.uri("/api/auth/signup")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(signupJson(username, displayName, password))
			.exchange();
	}

	private MvcTestResult login(String username, String password) {
		return this.mvc.post()
			.uri("/api/auth/login")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":" + json(username) + ",\"password\":" + json(password) + "}")
			.exchange();
	}

	private void failLogin(String username, int times) {
		for (int i = 0; i < times; i++) {
			assertError(login(username, "wrong1234"), HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", null);
		}
	}

	private MvcTestResult refresh(Cookie refreshToken) {
		return this.mvc.post().uri("/api/auth/refresh").with(csrf()).cookie(refreshToken).exchange();
	}

	private static String signupJson(String username, String displayName, String password) {
		return "{\"username\":" + json(username) + ",\"displayName\":" + json(displayName) + ",\"password\":"
				+ json(password) + "}";
	}

	private static String json(String value) {
		return (value == null) ? "null" : "\"" + value + "\"";
	}

	private static Cookie accessCookie(String token) {
		return new Cookie(AuthCookies.ACCESS_TOKEN, token);
	}

	private String signedWithOtherSecret() {
		byte[] otherSecret = "another-secret-another-secret-12345".getBytes(StandardCharsets.UTF_8);
		Instant now = this.clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject("1")
			.claim("username", "taro")
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofMinutes(15)))
			.build();
		return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(otherSecret, "HmacSHA256"))
			.algorithm(MacAlgorithm.HS256)
			.build()
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

	private static void assertError(MvcTestResult result, HttpStatus status, String code, String field) {
		assertThat(result).hasStatus(status);
		assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code);
		if (field != null) {
			assertThat(result).bodyJson().extractingPath("$.field").isEqualTo(field);
		}
		assertThat(result).bodyJson().extractingPath("$.message").asString().isNotEmpty();
	}

}
