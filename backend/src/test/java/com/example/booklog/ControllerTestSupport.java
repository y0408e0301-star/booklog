package com.example.booklog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.example.booklog.common.ErrorCode;

// Controller のテスト（@ControllerTest）で使う準備
public final class ControllerTestSupport {

	private ControllerTestSupport() {
	}

	// userId の人がログインしている状態にする（JWT の sub にユーザーの ID が入る。TokenService と同じ）。
	// spring-security-test の jwt() は使わない。あれは Authorization ヘッダーで送る前提で CSRF の確認を飛ばすが、
	// 本アプリは Cookie で送るので CSRF を確かめている（SecurityConfig）。飛ばすと csrf() の付け忘れに気づけない
	public static RequestPostProcessor loggedIn(long userId) {
		Jwt jwt = Jwt.withTokenValue("test-token").header("alg", "HS256").subject(String.valueOf(userId)).build();
		return authentication(new JwtAuthenticationToken(jwt, List.of()));
	}

	// 機能定義書 3.3 のエラーの形（code・message・field）とステータスで返したか
	public static void assertError(MvcTestResult result, ErrorCode code, @Nullable String field) {
		assertThat(result).hasStatus(code.status());
		assertThat(result).bodyJson().extractingPath("$.code").isEqualTo(code.name());
		assertThat(result).bodyJson().extractingPath("$.message").isEqualTo(code.message());
		assertThat(result).bodyJson().extractingPath("$.field").isEqualTo(field);
	}

	public static void assertError(MvcTestResult result, ErrorCode code) {
		assertError(result, code, null);
	}

}
