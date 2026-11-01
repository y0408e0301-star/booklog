package com.example.booklog.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

// アクセストークン・リフレッシュトークンを入れる Cookie（機能定義書 3.2）。
// どちらも HttpOnly（画面の JavaScript から読めない）・SameSite=Lax。公開先では Secure も付ける
@Component
class AuthCookies {

	static final String ACCESS_TOKEN = "access_token";

	static final String REFRESH_TOKEN = "refresh_token";

	// アクセストークンは API 全部に、リフレッシュトークンは取り直しとログアウトのときだけ送られるようにする
	private static final String ACCESS_TOKEN_PATH = "/api";

	private static final String REFRESH_TOKEN_PATH = "/api/auth";

	private final boolean secure;

	AuthCookies(AuthProperties properties) {
		this.secure = properties.secureCookie();
	}

	List<ResponseCookie> issue(TokenService.IssuedTokens tokens) {
		return List.of(
				cookie(ACCESS_TOKEN, tokens.accessToken(), ACCESS_TOKEN_PATH, TokenService.ACCESS_TOKEN_LIFETIME),
				cookie(REFRESH_TOKEN, tokens.refreshToken(), REFRESH_TOKEN_PATH, TokenService.REFRESH_TOKEN_LIFETIME));
	}

	// ログアウト・取り直しの失敗のとき。同じ名前・同じ Path で、すぐに切れる空の Cookie を送ると消える
	List<ResponseCookie> clear() {
		return List.of(cookie(ACCESS_TOKEN, "", ACCESS_TOKEN_PATH, Duration.ZERO),
				cookie(REFRESH_TOKEN, "", REFRESH_TOKEN_PATH, Duration.ZERO));
	}

	private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
		return ResponseCookie.from(name, value)
			.httpOnly(true)
			.secure(this.secure)
			.sameSite("Lax")
			.path(path)
			.maxAge(maxAge)
			.build();
	}

}
