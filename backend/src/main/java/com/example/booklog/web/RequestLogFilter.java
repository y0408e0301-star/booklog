package com.example.booklog.web;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// API のリクエストごとに 1 行のログを出す（基本設計書 2.3）。
// リクエスト ID は MDC に入れ、そのリクエストの間に出るログすべてに付ける。応答の X-Request-Id でも返し、問い合わせのときに探せるようにする。
// 401・403 も記録するため、Spring Security より前で動かす。パスワード・JWT・Cookie・本文は読まない（N-14）
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	// MDC の名前は ECS（Elastic Common Schema）の項目名に合わせる
	static final String REQUEST_ID = "http.request.id";

	static final String USER_ID = "user.id";

	private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

	private final Duration slowThreshold;

	RequestLogFilter(@Value("${app.logging.slow-request-threshold:1s}") Duration slowThreshold) {
		this.slowThreshold = slowThreshold;
	}

	// 画面のファイルと /actuator/health（公開先で死活の確認に何度も呼ばれる）は記録しない
	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = UUID.randomUUID().toString();
		MDC.put(REQUEST_ID, requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		long start = System.nanoTime();
		boolean failed = true;
		try {
			chain.doFilter(request, response);
			failed = false;
		}
		finally {
			// ApiExceptionHandler で受けきれずに例外が抜けたときは、Tomcat が 500 を返す
			int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
			log(request, status, Duration.ofNanos(System.nanoTime() - start));
			MDC.remove(REQUEST_ID);
			MDC.remove(USER_ID);
		}
	}

	// 閾値：5xx は ERROR、遅いリクエスト（既定 1 秒以上）は WARN、それ以外（4xx を含む）は INFO
	private void log(HttpServletRequest request, int status, Duration duration) {
		Level level = (status >= 500) ? Level.ERROR
				: (duration.compareTo(this.slowThreshold) >= 0) ? Level.WARN : Level.INFO;
		// クエリ文字列（検索語など）は出さない
		String path = request.getRequestURI();
		log.atLevel(level)
			.addKeyValue("http.request.method", request.getMethod())
			.addKeyValue("url.path", path)
			.addKeyValue("http.response.status_code", status)
			.addKeyValue("event.duration", duration.toNanos())
			.log("{} {} {} {}ms", request.getMethod(), path, status, duration.toMillis());
	}

	// ログインした人の ID を MDC に入れる。JWT を確かめた後（BearerTokenAuthenticationFilter
	// の後ろ）で動かす（SecurityConfig）
	public static void recordUser(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
			MDC.put(USER_ID, jwt.getSubject());
		}
		chain.doFilter(request, response);
	}

}
