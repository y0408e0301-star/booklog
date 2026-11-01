package com.example.booklog.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 構造化ログ（基本設計書 2.3）を、アプリ全体を起動して確かめる。
// 公開先と同じ設定（dev プロファイルなし）で、標準出力のログが 1 行 1 つの ECS 形式の JSON になること。
// 標準出力を調べるので、テストが落ちたときに MockMvc がリクエストの中身（パスワード・Cookie）を書き出す機能は止める
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTests {

	private static final String PASSWORD = "Zq9LogSecret77";

	// JWT（アクセストークン）を入れる Cookie（auth.AuthCookies）
	private static final String ACCESS_TOKEN = "access_token";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE users, refresh_tokens RESTART IDENTITY CASCADE");
	}

	@Test
	void リクエストのログはECS形式のJSONでリクエストIDとログインした人のIDが付く(CapturedOutput output) {
		Cookie access = signup().getResponse().getCookie(ACCESS_TOKEN);
		MvcTestResult me = this.mvc.get().uri("/api/auth/me").cookie(access).exchange();
		assertThat(me).hasStatusOk();
		Long userId = this.jdbc.queryForObject("SELECT id FROM users WHERE username = 'log_taro'", Long.class);

		JsonNode line = requestLog(output, "GET /api/auth/me 200");
		assertThat(line.path("ecs").path("version").asString()).isNotEmpty();
		assertThat(line.path("service").path("name").asString()).isEqualTo("booklog");
		assertThat(line.path("log").path("level").asString()).isEqualTo("INFO");
		assertThat(line.path("http").path("request").path("id").asString())
			.isEqualTo(me.getResponse().getHeader(RequestLogFilter.REQUEST_ID_HEADER));
		assertThat(line.path("http").path("request").path("method").asString()).isEqualTo("GET");
		assertThat(line.path("http").path("response").path("status_code").asInt()).isEqualTo(200);
		assertThat(line.path("url").path("path").asString()).isEqualTo("/api/auth/me");
		assertThat(line.path("event").path("duration").asLong()).isPositive();
		assertThat(line.path("user").path("id").asString()).isEqualTo(String.valueOf(userId));
	}

	@Test
	void ログインしていないリクエストにはユーザーのIDを付けない(CapturedOutput output) {
		assertThat(this.mvc.get().uri("/api/auth/me").exchange()).hasStatus(401);

		JsonNode line = requestLog(output, "GET /api/auth/me 401");
		assertThat(line.has("user")).isFalse();
	}

	// N-14
	@Test
	void パスワードとJWTはログに出さない(CapturedOutput output) {
		Cookie access = signup().getResponse().getCookie(ACCESS_TOKEN);
		this.mvc.post()
			.uri("/api/auth/login")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"log_taro\",\"password\":\"Zq9WrongPw88\"}")
			.exchange();

		requestLog(output, "POST /api/auth/login 401");
		assertThat(output.getOut()).doesNotContain(PASSWORD, "Zq9WrongPw88", access.getValue());
	}

	@Test
	void 標準出力のログはすべて1行1つのJSON(CapturedOutput output) {
		signup();

		List<String> lines = output.getOut().lines().filter((line) -> !line.isBlank()).toList();
		assertThat(lines).isNotEmpty().allSatisfy((line) -> assertThat(JSON.readTree(line).isObject()).isTrue());
	}

	private MvcTestResult signup() {
		MvcTestResult result = this.mvc.post()
			.uri("/api/auth/signup")
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"log_taro\",\"displayName\":\"たろう\",\"password\":\"" + PASSWORD + "\"}")
			.exchange();
		assertThat(result).hasStatus(201);
		return result;
	}

	// message が「メソッド パス ステータス」で始まるリクエストのログを 1 行だけ取り出す
	private static JsonNode requestLog(CapturedOutput output, String prefix) {
		List<JsonNode> found = output.getOut()
			.lines()
			.filter((line) -> line.startsWith("{"))
			.map(JSON::readTree)
			.filter((node) -> node.path("message").asString().startsWith(prefix + " "))
			.toList();
		assertThat(found).hasSize(1);
		return found.get(0);
	}

}
