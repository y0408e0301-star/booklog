package com.example.booklog.auth;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

import jakarta.servlet.http.Cookie;

// ユーザー登録・ログインの API（FN-01 6 章）の入口：URL・ステータス・JSON の形・Cookie。処理の中身は AuthServiceTests
@ControllerTest(AuthController.class)
class AuthControllerTests {

	private static final AuthService.LoginResult RESULT = new AuthService.LoginResult(
			new AuthService.Me("taro_y", "たろう"), new TokenService.IssuedTokens("new-access", "new-refresh"));

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	AuthService authService;

	@Test
	void ユーザーIDが使えるかはログインなしで聞ける() {
		when(this.authService.isUsernameAvailable("taro_y")).thenReturn(true);
		assertThat(this.mvc.get().uri("/api/auth/username-availability?username=taro_y").exchange()).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"available\":true}");
	}

	@Test
	void 登録すると201で自分の情報を返し3つのCookieを渡す() {
		when(this.authService.signup("taro_y", "たろう", "password1")).thenReturn(RESULT);

		MvcTestResult result = post("/api/auth/signup",
				"{\"username\":\"taro_y\",\"displayName\":\"たろう\",\"password\":\"password1\"}");

		assertThat(result).hasStatus(HttpStatus.CREATED)
			.bodyJson()
			.isStrictlyEqualTo("{\"username\":\"taro_y\",\"displayName\":\"たろう\"}");
		// ログインの Cookie と、新しくした CSRF トークン（画面の JavaScript が読むので HttpOnly なし。Raise timeline
		// の #63）
		assertThat(setCookies(result)).hasSize(3)
			.anySatisfy((cookie) -> assertThat(cookie).startsWith("access_token=new-access;").contains("HttpOnly"))
			.anySatisfy((cookie) -> assertThat(cookie).startsWith("refresh_token=new-refresh;").contains("HttpOnly"))
			.anySatisfy((cookie) -> assertThat(cookie).startsWith("XSRF-TOKEN=").doesNotContain("HttpOnly"));
	}

	@Test
	void 登録の入力の誤りは機能定義書のエラーの形で返す() {
		when(this.authService.signup("x", "たろう", "password1"))
			.thenThrow(new ApiException(ErrorCode.USERNAME_INVALID, "username"));

		MvcTestResult result = post("/api/auth/signup",
				"{\"username\":\"x\",\"displayName\":\"たろう\",\"password\":\"password1\"}");

		assertError(result, ErrorCode.USERNAME_INVALID, "username");
		assertThat(setCookies(result)).isEmpty();
	}

	@Test
	void JSONが読めなければ400のBAD_REQUEST() {
		assertError(post("/api/auth/signup", "{\"username\":"), ErrorCode.BAD_REQUEST);
		verifyNoInteractions(this.authService);
	}

	@Test
	void ログインすると200で3つのCookieを渡す() {
		when(this.authService.login("taro_y", "password1")).thenReturn(RESULT);

		MvcTestResult result = post("/api/auth/login", "{\"username\":\"taro_y\",\"password\":\"password1\"}");

		assertThat(result).hasStatusOk().bodyJson().extractingPath("$.username").isEqualTo("taro_y");
		assertThat(setCookies(result)).hasSize(3).anySatisfy((cookie) -> assertThat(cookie).startsWith("XSRF-TOKEN="));
	}

	@Test
	void ロック中は423のLOGIN_LOCKED() {
		when(this.authService.login("taro_y", "password1")).thenThrow(new ApiException(ErrorCode.LOGIN_LOCKED));
		assertError(post("/api/auth/login", "{\"username\":\"taro_y\",\"password\":\"password1\"}"),
				ErrorCode.LOGIN_LOCKED);
	}

	@Test
	void 取り直しはCookieのリフレッシュトークンを使い新しいCookieを渡す() {
		when(this.authService.refresh("old-refresh")).thenReturn(RESULT);

		MvcTestResult result = this.mvc.post()
			.uri("/api/auth/refresh")
			.with(csrf())
			.cookie(new Cookie("refresh_token", "old-refresh"))
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(setCookies(result))
			.anySatisfy((cookie) -> assertThat(cookie).startsWith("refresh_token=new-refresh;"));
	}

	@Test
	void 取り直しに失敗したら401とともに2つのCookieを消す() {
		when(this.authService.refresh(null)).thenThrow(new ApiException(ErrorCode.REFRESH_FAILED));

		MvcTestResult result = this.mvc.post().uri("/api/auth/refresh").with(csrf()).exchange();

		assertError(result, ErrorCode.REFRESH_FAILED);
		assertThat(setCookies(result)).hasSize(2).allSatisfy((cookie) -> assertThat(cookie).contains("Max-Age=0"));
	}

	@Test
	void ログアウトは204でこのブラウザのトークンを無効にしCookieを消す() {
		MvcTestResult result = this.mvc.post()
			.uri("/api/auth/logout")
			.with(csrf())
			.with(loggedIn(1))
			.cookie(new Cookie("refresh_token", "my-refresh"))
			.exchange();

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		verify(this.authService).logout(1, "my-refresh");
		assertThat(setCookies(result)).hasSize(2).allSatisfy((cookie) -> assertThat(cookie).contains("Max-Age=0"));
	}

	@Test
	void 自分の情報はJWTのユーザーのIDで探す() {
		when(this.authService.me(7)).thenReturn(new AuthService.Me("hanako", "はなこ"));
		assertThat(this.mvc.get().uri("/api/auth/me").with(loggedIn(7)).exchange()).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"username\":\"hanako\",\"displayName\":\"はなこ\"}");
	}

	@Test
	void ログアウトと自分の情報はログインが要る() {
		assertError(this.mvc.get().uri("/api/auth/me").exchange(), ErrorCode.UNAUTHENTICATED);
		assertError(this.mvc.post().uri("/api/auth/logout").with(csrf()).exchange(), ErrorCode.UNAUTHENTICATED);
		verifyNoInteractions(this.authService);
	}

	private MvcTestResult post(String uri, String json) {
		return this.mvc.post().uri(uri).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
	}

	private static List<String> setCookies(MvcTestResult result) {
		return List.copyOf(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
	}

}
