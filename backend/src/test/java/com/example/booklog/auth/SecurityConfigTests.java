package com.example.booklog.auth;

import static com.example.booklog.ControllerTestSupport.assertError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.ControllerTest;
import com.example.booklog.MutableClock;
import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;

import jakarta.servlet.http.Cookie;

// ログインの確かめ方（機能定義書 3.2）。Cookie の access_token の JWT を本物の部品で作って送る
@ControllerTest(AuthController.class)
class SecurityConfigTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JwtEncoder jwtEncoder;

	@Autowired
	MutableClock clock;

	@MockitoBean
	AuthService authService;

	@BeforeEach
	void setUp() {
		this.clock.reset();
		when(this.authService.me(1)).thenReturn(new AuthService.Me("taro_y", "たろう"));
	}

	@Test
	void 正しいJWTのCookieがあればログインしている() {
		assertThat(me(accessToken())).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("taro_y");
	}

	@Test
	void Cookieがなければ401のUNAUTHENTICATED() {
		assertError(this.mvc.get().uri("/api/auth/me").exchange(), ErrorCode.UNAUTHENTICATED);
	}

	// N-19
	@Test
	void 十五分たったJWTは401のTOKEN_EXPIREDで画面に取り直させる() {
		Cookie token = accessToken();
		this.clock.advance(Duration.ofMinutes(15).minusSeconds(1));
		assertThat(me(token)).hasStatusOk();
		this.clock.advance(Duration.ofSeconds(1));
		assertError(me(token), ErrorCode.TOKEN_EXPIRED);
	}

	@Test
	void 書き換えたJWTやほかの秘密の値で署名したJWTはUNAUTHENTICATED() {
		String value = accessToken().getValue();
		assertError(me(new Cookie("access_token", value.substring(0, value.length() - 2) + "xx")),
				ErrorCode.UNAUTHENTICATED);
		assertError(me(new Cookie("access_token", "not-a-jwt")), ErrorCode.UNAUTHENTICATED);

		JwtEncoder other = new JwtConfig()
			.jwtEncoder(new AuthProperties("another-jwt-secret-0123456789abcdefgh", false));
		String forged = new TokenService(other, mock(RefreshTokenRepository.class), this.clock)
			.issue(TestEntities.user(1, "taro_y"))
			.accessToken();
		assertError(me(new Cookie("access_token", forged)), ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void ログインがいらないAPIでは期限切れのCookieが残っていても読まない() {
		when(this.authService.login(any(), any())).thenReturn(new AuthService.LoginResult(
				new AuthService.Me("taro_y", "たろう"), new TokenService.IssuedTokens("a", "r")));
		Cookie expired = accessToken();
		this.clock.advance(Duration.ofHours(1));

		MvcTestResult result = this.mvc.post()
			.uri("/api/auth/login")
			.with(csrf())
			.cookie(expired)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"taro_y\",\"password\":\"password1\"}")
			.exchange();

		assertThat(result).hasStatusOk();
	}

	@Test
	void CSRFトークンがなければGET以外は403のFORBIDDEN() {
		MvcTestResult result = this.mvc.post()
			.uri("/api/auth/login")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"taro_y\",\"password\":\"password1\"}")
			.exchange();
		assertError(result, ErrorCode.FORBIDDEN);
	}

	// TC-01-7
	@Test
	void ログインしていてもCSRFトークンがなければ403() {
		assertError(this.mvc.post().uri("/api/auth/logout").cookie(accessToken()).exchange(), ErrorCode.FORBIDDEN);
	}

	@Test
	void セッションは作らない() {
		assertThat(me(accessToken()).getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.noneMatch((cookie) -> cookie.startsWith("JSESSIONID"));
	}

	private MvcTestResult me(Cookie accessToken) {
		return this.mvc.get().uri("/api/auth/me").cookie(accessToken).exchange();
	}

	// 今の時刻で users.id = 1 の人に作ったアクセストークン
	private Cookie accessToken() {
		String token = new TokenService(this.jwtEncoder, mock(RefreshTokenRepository.class), this.clock)
			.issue(TestEntities.user(1, "taro_y"))
			.accessToken();
		return new Cookie("access_token", token);
	}

}
