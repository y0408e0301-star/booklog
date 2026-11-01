package com.example.booklog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// リクエストごとのログ（基本設計書 2.3）。出したログを Logback の appender で受け取り、レベル・項目・MDC を確かめる
class RequestLogFilterTests {

	private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);

	private final CapturingAppender appender = new CapturingAppender();

	@BeforeEach
	void setUp() {
		this.appender.start();
		this.logger.addAppender(this.appender);
	}

	@AfterEach
	void tearDown() {
		this.logger.detachAppender(this.appender);
		SecurityContextHolder.clearContext();
		MDC.clear();
	}

	@Test
	void APIのリクエストごとに1行のログを出しリクエストIDを応答のヘッダーでも返す() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"), response, new MockFilterChain());

		ILoggingEvent event = onlyEvent();
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getFormattedMessage()).matches("GET /api/reviews/1 200 \\d+ms");
		Map<String, Object> pairs = pairs(event);
		assertThat(pairs).containsEntry("http.request.method", "GET")
			.containsEntry("url.path", "/api/reviews/1")
			.containsEntry("http.response.status_code", 200)
			.containsKey("event.duration");
		String requestId = response.getHeader(RequestLogFilter.REQUEST_ID_HEADER);
		assertThat(requestId).isNotBlank();
		assertThat(event.getMDCPropertyMap()).containsEntry(RequestLogFilter.REQUEST_ID, requestId);
	}

	@Test
	void リクエストが終わったらMDCを空に戻す() throws Exception {
		filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"), new MockHttpServletResponse(),
				new MockFilterChain());

		assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isNull();
		assertThat(MDC.get(RequestLogFilter.USER_ID)).isNull();
	}

	@Test
	void リクエストごとに違うリクエストIDを付ける() throws Exception {
		MockHttpServletResponse first = new MockHttpServletResponse();
		MockHttpServletResponse second = new MockHttpServletResponse();
		filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"), first, new MockFilterChain());
		filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"), second, new MockFilterChain());

		assertThat(first.getHeader(RequestLogFilter.REQUEST_ID_HEADER))
			.isNotEqualTo(second.getHeader(RequestLogFilter.REQUEST_ID_HEADER));
	}

	@Test
	void クエリ文字列はログに出さない() throws Exception {
		MockHttpServletRequest request = request("GET", "/api/search/users");
		request.setQueryString("q=taro");
		filter(Duration.ofSeconds(1)).doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

		ILoggingEvent event = onlyEvent();
		assertThat(event.getFormattedMessage()).doesNotContain("taro");
		assertThat(pairs(event)).containsEntry("url.path", "/api/search/users");
	}

	@Test
	void 失敗した4xxはINFOのまま出す() throws Exception {
		filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"), new MockHttpServletResponse(),
				respondWith(HttpServletResponse.SC_NOT_FOUND));

		ILoggingEvent event = onlyEvent();
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(pairs(event)).containsEntry("http.response.status_code", 404);
	}

	@Test
	void 応答が5xxならERRORで出す() throws Exception {
		filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"), new MockHttpServletResponse(),
				respondWith(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));

		assertThat(onlyEvent().getLevel()).isEqualTo(Level.ERROR);
	}

	@Test
	void 例外が抜けたときは500としてERRORで出し例外はそのまま投げる() {
		MockFilterChain chain = new MockFilterChain(new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) {
				throw new IllegalStateException("思いがけない失敗");
			}
		});

		assertThatThrownBy(() -> filter(Duration.ofSeconds(1)).doFilter(request("GET", "/api/reviews/1"),
				new MockHttpServletResponse(), chain))
			.isInstanceOf(IllegalStateException.class);
		ILoggingEvent event = onlyEvent();
		assertThat(event.getLevel()).isEqualTo(Level.ERROR);
		assertThat(pairs(event)).containsEntry("http.response.status_code", 500);
		assertThat(MDC.get(RequestLogFilter.REQUEST_ID)).isNull();
	}

	@Test
	void 閾値以上かかったリクエストはWARNで出す() throws Exception {
		filter(Duration.ZERO).doFilter(request("GET", "/api/reviews/1"), new MockHttpServletResponse(),
				new MockFilterChain());

		assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
	}

	@Test
	void 遅くても5xxならERRORを優先する() throws Exception {
		filter(Duration.ZERO).doFilter(request("GET", "/api/reviews/1"), new MockHttpServletResponse(),
				respondWith(HttpServletResponse.SC_SERVICE_UNAVAILABLE));

		assertThat(onlyEvent().getLevel()).isEqualTo(Level.ERROR);
	}

	@Test
	void API以外のリクエストは記録せずヘッダーも付けない() throws Exception {
		for (String path : List.of("/", "/assets/index.js", "/actuator/health", "/users/taro")) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			filter(Duration.ZERO).doFilter(request("GET", path), response, new MockFilterChain());
			assertThat(response.getHeader(RequestLogFilter.REQUEST_ID_HEADER)).as(path).isNull();
		}
		assertThat(this.appender.events).isEmpty();
	}

	@Test
	void ログインした人のIDをMDCに入れる() throws Exception {
		Jwt jwt = Jwt.withTokenValue("token")
			.header("alg", "HS256")
			.subject("42")
			.issuedAt(Instant.parse("2026-10-24T00:00:00Z"))
			.expiresAt(Instant.parse("2026-10-24T00:15:00Z"))
			.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		List<String> seen = new ArrayList<>();

		RequestLogFilter.recordUser(request("GET", "/api/auth/me"), new MockHttpServletResponse(),
				(req, res) -> seen.add(MDC.get(RequestLogFilter.USER_ID)));

		assertThat(seen).containsExactly("42");
	}

	@Test
	void ログインしていなければユーザーのIDは入れない() throws Exception {
		List<String> seen = new ArrayList<>();

		RequestLogFilter.recordUser(request("POST", "/api/auth/login"), new MockHttpServletResponse(),
				(req, res) -> seen.add(MDC.get(RequestLogFilter.USER_ID)));

		assertThat(seen).containsExactly((String) null);
	}

	private static RequestLogFilter filter(Duration slowThreshold) {
		return new RequestLogFilter(slowThreshold);
	}

	private static MockHttpServletRequest request(String method, String path) {
		return new MockHttpServletRequest(method, path);
	}

	private static MockFilterChain respondWith(int status) {
		return new MockFilterChain(new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse resp) {
				resp.setStatus(status);
			}
		});
	}

	private ILoggingEvent onlyEvent() {
		assertThat(this.appender.events).hasSize(1);
		return this.appender.events.get(0);
	}

	private static Map<String, Object> pairs(ILoggingEvent event) {
		List<KeyValuePair> pairs = event.getKeyValuePairs();
		assertThat(pairs).isNotNull();
		Map<String, Object> map = new LinkedHashMap<>();
		pairs.forEach((pair) -> map.put(pair.key, pair.value));
		return map;
	}

	// MDC はログを出した時点の中身を写しておく（フィルターは出した直後に MDC を空にするため）
	private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

		private final List<ILoggingEvent> events = new ArrayList<>();

		@Override
		protected void append(ILoggingEvent event) {
			event.prepareForDeferredProcessing();
			this.events.add(event);
		}

	}

}
