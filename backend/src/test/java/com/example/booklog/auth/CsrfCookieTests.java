package com.example.booklog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;

// 画面と同じ手順で CSRF トークンを受け取り、送り返せることを、実際に HTTP で確かめる。
// MockMvc では spring-security-test が CSRF トークンの保存先を差し替えるため、Cookie の発行を確かめられない。
// サーバーはテストの間だけ空いているポートで起動する（開発用の 8080 とはぶつからない）
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
class CsrfCookieTests {

	@LocalServerPort
	int port;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void 最初のGETで受け取ったXSRF_TOKENをヘッダーで送り返すとPOSTできる() throws Exception {
		// 画面は起動したときに GET /api/auth/me を呼ぶ。ログインしていなくても XSRF-TOKEN の Cookie を受け取る
		HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me")).GET());
		assertThat(me.statusCode()).isEqualTo(401);
		String setCookie = me.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((value) -> value.startsWith("XSRF-TOKEN="))
			.findFirst()
			.orElseThrow();
		// 画面の JavaScript が読んでヘッダーに入れるため、HttpOnly は付けない
		assertThat(setCookie).doesNotContainIgnoringCase("HttpOnly");
		String token = setCookie.substring("XSRF-TOKEN=".length(), setCookie.indexOf(';'));

		assertThat(signup("csrf_ok", token, token).statusCode()).isEqualTo(201);
		assertThat(signup("csrf_ng", token, "wrong").statusCode()).isEqualTo(403);
	}

	@Test
	void ログインした状態のAPIではXSRF_TOKENを入れ替えず_登録とログインのときだけ入れ替える() throws Exception {
		String token = xsrfToken(send(HttpRequest.newBuilder(uri("/api/auth/me")).GET())).orElseThrow();

		// 登録した応答で新しいトークンに入れ替わる（ログインの前に仕込まれたトークンを使い続けさせない）
		HttpResponse<String> signup = signup("csrf_keep", token, token);
		assertThat(signup.statusCode()).isEqualTo(201);
		String afterSignup = xsrfToken(signup).orElseThrow();
		assertThat(afterSignup).isNotEqualTo(token);
		String accessToken = cookie(signup, AuthCookies.ACCESS_TOKEN);
		String cookies = "XSRF-TOKEN=" + afterSignup + "; access_token=" + accessToken;

		// ログインした状態の GET では入れ替わらない。入れ替わると、読み込みの途中で送った POST が 403 になる（Raise timeline の #63）
		for (int i = 0; i < 3; i++) {
			HttpResponse<String> me = send(HttpRequest.newBuilder(uri("/api/auth/me")).header("Cookie", cookies).GET());
			assertThat(me.statusCode()).isEqualTo(200);
			assertThat(xsrfToken(me)).isEmpty();
		}

		// 同じトークンで、続けて書き込める（書き込む API の例としてログアウトを使う）
		HttpResponse<String> logout = send(HttpRequest.newBuilder(uri("/api/auth/logout"))
			.headers("Cookie", cookies, "X-XSRF-TOKEN", afterSignup)
			.POST(HttpRequest.BodyPublishers.noBody()));
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(xsrfToken(logout)).isEmpty();

		// ログインした応答でも入れ替わる
		HttpResponse<String> login = send(HttpRequest.newBuilder(uri("/api/auth/login"))
			.headers("Content-Type", "application/json", "Cookie", "XSRF-TOKEN=" + afterSignup, "X-XSRF-TOKEN",
					afterSignup)
			.POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"csrf_keep\",\"password\":\"password1\"}")));
		assertThat(login.statusCode()).isEqualTo(200);
		assertThat(xsrfToken(login)).hasValueSatisfying((value) -> assertThat(value).isNotEqualTo(afterSignup));
	}

	// 応答で受け取った XSRF-TOKEN の Cookie の値。消す Cookie（空の値）は除く
	private static Optional<String> xsrfToken(HttpResponse<String> response) {
		return response.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((value) -> value.startsWith("XSRF-TOKEN=") && !value.startsWith("XSRF-TOKEN=;"))
			.map((value) -> value.substring("XSRF-TOKEN=".length(), value.indexOf(';')))
			.reduce((first, last) -> last);
	}

	private static String cookie(HttpResponse<String> response, String name) {
		return response.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((value) -> value.startsWith(name + "="))
			.map((value) -> value.substring(name.length() + 1, value.indexOf(';')))
			.findFirst()
			.orElseThrow();
	}

	private HttpResponse<String> signup(String username, String cookieToken, String headerToken)
			throws IOException, InterruptedException {
		String body = "{\"username\":\"" + username + "\",\"displayName\":\"名前\",\"password\":\"password1\"}";
		return send(HttpRequest.newBuilder(uri("/api/auth/signup"))
			.headers("Content-Type", "application/json", "Cookie", "XSRF-TOKEN=" + cookieToken, "X-XSRF-TOKEN",
					headerToken)
			.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
		return this.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + this.port + path);
	}

}
