package com.example.booklog.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

// 退会の API（機能定義書 FN-01 6 章）。URL が /api/auth の下にないので、AuthController とは分ける
@Tag(name = "FN-01 ユーザー登録・ログイン")
@RestController
class WithdrawalController {

	private final AuthService authService;

	private final AuthCookies cookies;

	WithdrawalController(AuthService authService, AuthCookies cookies) {
		this.authService = authService;
		this.cookies = cookies;
	}

	@Operation(operationId = "withdraw", summary = "退会する",
			description = "パスワードを確かめ、その人のデータをすべて消して、Cookie も消す。本は消さない。元に戻せない")
	@ApiResponse(responseCode = "204", description = "退会した")
	@DeleteMapping("/api/me")
	ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt, @RequestBody WithdrawRequest request) {
		this.authService.withdraw(Long.parseLong(jwt.getSubject()), request.password());
		ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.NO_CONTENT);
		this.cookies.clear().forEach((cookie) -> response.header(HttpHeaders.SET_COOKIE, cookie.toString()));
		return response.build();
	}

	record WithdrawRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String password) {
	}

}
