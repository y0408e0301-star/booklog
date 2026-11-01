package com.example.booklog.auth;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// ユーザー登録・ログインの API（機能定義書 FN-01 6 章）
@Tag(name = "FN-01 ユーザー登録・ログイン")
@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService authService;

	private final AuthCookies cookies;

	private final CsrfTokenRepository csrfTokenRepository;

	AuthController(AuthService authService, AuthCookies cookies, CsrfTokenRepository csrfTokenRepository) {
		this.authService = authService;
		this.cookies = cookies;
		this.csrfTokenRepository = csrfTokenRepository;
	}

	// ユーザー ID の入力欄から離れたときに、使えるかどうかを確かめる
	@Operation(summary = "ユーザー ID が使えるか")
	@SecurityRequirements
	@GetMapping("/username-availability")
	UsernameAvailability usernameAvailability(@RequestParam(required = false) @Nullable String username) {
		return new UsernameAvailability(this.authService.isUsernameAvailable(username));
	}

	@Operation(summary = "ユーザー登録", description = "登録したらそのままログインし、access_token・refresh_token の Cookie を返す")
	@ApiResponse(responseCode = "201", description = "登録した")
	@SecurityRequirements
	@PostMapping("/signup")
	ResponseEntity<AuthService.Me> signup(@RequestBody SignupRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		AuthService.LoginResult result = this.authService.signup(request.username(), request.displayName(),
				request.password());
		signedIn(result, httpRequest, httpResponse);
		return ResponseEntity.status(HttpStatus.CREATED).body(result.me());
	}

	@Operation(summary = "ログイン", description = "access_token・refresh_token の Cookie を返す。続けて失敗すると 15 分ロックする（423）")
	@SecurityRequirements
	@PostMapping("/login")
	ResponseEntity<AuthService.Me> login(@RequestBody LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse httpResponse) {
		AuthService.LoginResult result = this.authService.login(request.username(), request.password());
		signedIn(result, httpRequest, httpResponse);
		return ResponseEntity.ok(result.me());
	}

	// 取り直しに失敗したときは、使えなくなった Cookie を消してから 401 を返す
	@Operation(summary = "JWT の取り直し", description = "refresh_token の Cookie で、access_token・refresh_token を取り直す")
	@ApiResponse(responseCode = "200", description = "取り直した",
			content = @Content(schema = @Schema(implementation = AuthService.Me.class)))
	@SecurityRequirements
	@PostMapping("/refresh")
	ResponseEntity<?> refresh(@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) @Nullable String token) {
		AuthService.LoginResult result;
		try {
			result = this.authService.refresh(token);
		}
		catch (ApiException ex) {
			return withCookies(ResponseEntity.status(ex.code().status()), this.cookies.clear())
				.body(ErrorResponse.of(ex.code(), null));
		}
		return withCookies(ResponseEntity.ok(), this.cookies.issue(result.tokens())).body(result.me());
	}

	@Operation(summary = "ログアウト", description = "refresh_token を無効にし、Cookie を消す")
	@ApiResponse(responseCode = "204", description = "ログアウトした")
	@PostMapping("/logout")
	ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt,
			@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) @Nullable String token) {
		this.authService.logout(userId(jwt), token);
		return withCookies(ResponseEntity.status(HttpStatus.NO_CONTENT), this.cookies.clear()).build();
	}

	@Operation(summary = "ログインしている自分")
	@GetMapping("/me")
	AuthService.Me me(@AuthenticationPrincipal Jwt jwt) {
		return this.authService.me(userId(jwt));
	}

	// ログインした（登録した）ときの Cookie。access_token・refresh_token を出し、CSRF トークンも新しくする。
	// 新しくするのは、ログインの前に他人が Cookie に仕込んだトークンを、ログインした後も使い続けさせないため
	// （Spring Security の CsrfAuthenticationStrategy と同じ考え。SecurityConfig）。
	// CSRF トークンの Cookie はサーブレットの応答に書かれ、ResponseEntity の Set-Cookie はそれを上書きするので、
	// ここではすべての Cookie をサーブレットの応答に足す
	private void signedIn(AuthService.LoginResult result, HttpServletRequest request, HttpServletResponse response) {
		this.cookies.issue(result.tokens())
			.forEach((cookie) -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
		this.csrfTokenRepository.saveToken(this.csrfTokenRepository.generateToken(request), request, response);
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	private static ResponseEntity.BodyBuilder withCookies(ResponseEntity.BodyBuilder builder,
			List<ResponseCookie> cookies) {
		cookies.forEach((cookie) -> builder.header(HttpHeaders.SET_COOKIE, cookie.toString()));
		return builder;
	}

	record UsernameAvailability(boolean available) {
	}

	record SignupRequest(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "前後の空白を除いて 3〜15 文字の半角英数字と「_」（A-2）") @Nullable String username,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "前後の空白を除いて 1〜30 文字（A-4）") @Nullable String displayName,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "8〜64 文字の半角文字で、英字と数字を 1 文字以上ずつ含む（A-3）") @Nullable String password) {
	}

	record LoginRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String username,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String password) {
	}

}
