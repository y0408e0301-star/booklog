package com.example.booklog.auth;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 退会の API の入口（FN-01 6 章）：URL・ステータス・Cookie。処理の中身は AuthServiceTests・WithdrawalApiTests
@ControllerTest(WithdrawalController.class)
class WithdrawalControllerTests {

	private static final String BODY = "{\"password\":\"password1\"}";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	AuthService authService;

	@Test
	void 退会すると204で両方のCookieを消す() {
		MvcTestResult result = this.mvc.delete()
			.uri("/api/me")
			.with(loggedIn(1))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(BODY)
			.exchange();

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(result.getResponse().getCookie(AuthCookies.ACCESS_TOKEN).getMaxAge()).isZero();
		assertThat(result.getResponse().getCookie(AuthCookies.REFRESH_TOKEN).getMaxAge()).isZero();
		verify(this.authService).withdraw(1, "password1");
	}

	// TC-01-13
	@Test
	void パスワードが違えば400でCookieを消さない() {
		doThrow(new ApiException(ErrorCode.PASSWORD_WRONG, "password")).when(this.authService).withdraw(1, "password1");
		MvcTestResult result = this.mvc.delete()
			.uri("/api/me")
			.with(loggedIn(1))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content(BODY)
			.exchange();

		assertError(result, ErrorCode.PASSWORD_WRONG, "password");
		assertThat(result.getResponse().getCookie(AuthCookies.ACCESS_TOKEN)).isNull();
	}

	@Test
	void ログインとCSRFトークンが要る() {
		assertThat(this.mvc.delete().uri("/api/me").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(BODY))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.delete()
			.uri("/api/me")
			.with(loggedIn(1))
			.contentType(MediaType.APPLICATION_JSON)
			.content(BODY)).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.authService);
	}

}
