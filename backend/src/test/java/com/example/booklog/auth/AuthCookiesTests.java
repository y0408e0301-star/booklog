package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

// トークンを入れる Cookie（機能定義書 3.2）
class AuthCookiesTests {

	private static final String SECRET = "test-only-jwt-secret-0123456789abcdef";

	private final AuthCookies cookies = new AuthCookies(new AuthProperties(SECRET, false));

	// N-19
	// TC-01-8
	@Test
	void 二つのCookieはHttpOnlyでSameSiteはLax() {
		List<ResponseCookie> issued = this.cookies.issue(new TokenService.IssuedTokens("access", "refresh"));
		assertThat(issued).hasSize(2).allSatisfy((cookie) -> {
			assertThat(cookie.isHttpOnly()).isTrue();
			assertThat(cookie.getSameSite()).isEqualTo("Lax");
			assertThat(cookie.isSecure()).isFalse();
		});
	}

	@Test
	void アクセストークンはAPI全部に15分間送る() {
		ResponseCookie access = this.cookies.issue(new TokenService.IssuedTokens("access", "refresh")).get(0);
		assertThat(access.getName()).isEqualTo("access_token");
		assertThat(access.getValue()).isEqualTo("access");
		assertThat(access.getPath()).isEqualTo("/api");
		assertThat(access.getMaxAge()).isEqualTo(Duration.ofMinutes(15));
	}

	// A-8
	@Test
	void リフレッシュトークンは取り直しとログアウトにだけ14日間送る() {
		ResponseCookie refresh = this.cookies.issue(new TokenService.IssuedTokens("access", "refresh")).get(1);
		assertThat(refresh.getName()).isEqualTo("refresh_token");
		assertThat(refresh.getValue()).isEqualTo("refresh");
		assertThat(refresh.getPath()).isEqualTo("/api/auth");
		assertThat(refresh.getMaxAge()).isEqualTo(Duration.ofDays(14));
	}

	@Test
	void 消すときは同じ名前とPathで空の値をすぐ切れるようにする() {
		List<ResponseCookie> cleared = this.cookies.clear();
		assertThat(cleared).extracting(ResponseCookie::getName).containsExactly("access_token", "refresh_token");
		assertThat(cleared).extracting(ResponseCookie::getPath).containsExactly("/api", "/api/auth");
		assertThat(cleared).allSatisfy((cookie) -> {
			assertThat(cookie.getValue()).isEmpty();
			assertThat(cookie.getMaxAge()).isZero();
		});
	}

	@Test
	void 公開先ではSecureを付ける() {
		AuthCookies secure = new AuthCookies(new AuthProperties(SECRET, true));
		assertThat(secure.issue(new TokenService.IssuedTokens("a", "r"))).allMatch(ResponseCookie::isSecure);
		assertThat(secure.clear()).allMatch(ResponseCookie::isSecure);
	}

}
