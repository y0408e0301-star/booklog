package com.example.booklog.auth;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.WebUtils;

import com.example.booklog.common.ErrorCode;
import com.example.booklog.common.ErrorResponse;
import com.example.booklog.user.UserRepository;
import com.example.booklog.web.RequestLogFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

// ログインの確かめ方（機能定義書 3.2）。
// セッションは使わず（STATELESS）、Cookie の access_token に入った JWT を毎回確かめる
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	// ログインしていなくても使える API（機能定義書 3.2「ログインがいらない API」）
	private static final RequestMatcher PUBLIC_API = new OrRequestMatcher(
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/signup"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/login"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/auth/refresh"),
			PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/api/auth/username-availability"));

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, UserRepository users,
			JsonMapper jsonMapper, CsrfTokenRepository csrfTokenRepository) {
		AuthenticationEntryPoint unauthorized = (request, response, ex) -> writeError(response, jsonMapper,
				unauthorized(ex));
		http.authorizeHttpRequests((authorize) -> authorize.requestMatchers(PUBLIC_API)
			.permitAll()
			.requestMatchers("/api/**")
			.authenticated()
			// /api 以外（/actuator/health と、あとで配る画面のファイル）は誰でも読める
			.anyRequest()
			.permitAll())
			.sessionManagement((session) -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.requestCache((cache) -> cache.disable())
			// Cookie は自動で送られるため CSRF 対策は続ける。XSRF-TOKEN の Cookie の値を、
			// 画面が X-XSRF-TOKEN ヘッダーで送り返す（GET 以外のすべての API で確かめる）。
			// 既定ではログインした状態のリクエストのたびにトークンを作り直す（STATELESS なので、毎回「ログインした」と
			// みなされる）。読み込みの途中に送った POST が 403 になるので作り直さず、登録・ログインのときだけ
			// AuthController が作り直す（Raise timeline の #63）
			.csrf((csrf) -> csrf.spa()
				.csrfTokenRepository(csrfTokenRepository)
				.sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy()))
			// spa() は Cookie を出すのをトークンが使われるまで遅らせる。画面が最初の GET で受け取れるよう、毎回出す
			.addFilterAfter(SecurityConfig::loadCsrfToken, CsrfFilter.class)
			.addFilter(accessTokenFilter(jwtDecoder, users, unauthorized))
			// リクエストのログにログインした人の ID を付ける（基本設計書 2.3）
			.addFilterAfter(RequestLogFilter::recordUser, BearerTokenAuthenticationFilter.class)
			.exceptionHandling((exceptions) -> exceptions.authenticationEntryPoint(unauthorized)
				// CSRF トークンがない・合わないときなど
				.accessDeniedHandler((request, response, ex) -> writeError(response, jsonMapper, ErrorCode.FORBIDDEN)));
		return http.build();
	}

	// XSRF-TOKEN の Cookie（spa() の既定と同じく、画面の JavaScript が読めるよう HttpOnly を付けない）
	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return CookieCsrfTokenRepository.withHttpOnlyFalse();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private static void loadCsrfToken(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
			token.getToken();
		}
		chain.doFilter(request, response);
	}

	// Cookie の access_token の JWT を確かめて、ログインした人にする。
	// Spring Security の oauth2ResourceServer() は使わない。あれはトークンを読めたリクエストを CSRF の確認から外す
	// （Authorization ヘッダーで送る前提のため）が、本アプリは Cookie で送るので、外すと CSRF を防げなくなる
	private static BearerTokenAuthenticationFilter accessTokenFilter(JwtDecoder jwtDecoder, UserRepository users,
			AuthenticationEntryPoint unauthorized) {
		// 退会した人の JWT は、期限が残っていても使えない（FN-01 5 章の 9・9 章）
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		JwtAuthenticationProvider provider = new JwtAuthenticationProvider(jwtDecoder);
		provider.setJwtAuthenticationConverter((jwt) -> {
			if (!users.existsById(Long.valueOf(jwt.getSubject()))) {
				throw new InvalidBearerTokenException("ユーザーがいません");
			}
			return converter.convert(jwt);
		});
		BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(new ProviderManager(provider),
				SecurityConfig::readAccessToken);
		filter.setAuthenticationFailureHandler(unauthorized::commence);
		return filter;
	}

	// JWT は Authorization ヘッダーではなく Cookie の access_token から読む。
	// ログインがいらない API では読まない（期限切れの Cookie が残っていても、ログイン・取り直しができるように）
	private static @Nullable BearerTokenAuthenticationToken readAccessToken(HttpServletRequest request) {
		if (PUBLIC_API.matches(request)) {
			return null;
		}
		Cookie cookie = WebUtils.getCookie(request, AuthCookies.ACCESS_TOKEN);
		return (cookie == null || cookie.getValue().isEmpty()) ? null
				: new BearerTokenAuthenticationToken(cookie.getValue());
	}

	// JWT の期限切れなら TOKEN_EXPIRED（画面が取り直してやり直す）、それ以外は UNAUTHENTICATED
	private static ErrorCode unauthorized(AuthenticationException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof JwtValidationException invalid && invalid.getErrors()
				.stream()
				.anyMatch((error) -> JwtConfig.TOKEN_EXPIRED_ERROR.equals(error.getErrorCode()))) {
				return ErrorCode.TOKEN_EXPIRED;
			}
		}
		return ErrorCode.UNAUTHENTICATED;
	}

	private static void writeError(HttpServletResponse response, JsonMapper jsonMapper, ErrorCode code)
			throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, null));
	}

}
