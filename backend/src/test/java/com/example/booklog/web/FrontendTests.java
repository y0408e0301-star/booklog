package com.example.booklog.web;

import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;

// 画面のファイルの配り方（FrontendConfig）。テストでは src/test/resources/static/ の小さな index.html と
// assets/ の JS を、jar に入る画面の代わりに使う（Content-Type に charset が付かないので、目印は英数字にする）
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class FrontendTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void トップはindexHtmlに回す() {
		// Spring Boot のウェルカムページが index.html に forward する（MockMvc は forward の先までは処理しない）
		assertThat(this.mvc.get().uri("/").exchange()).hasStatus(HttpStatus.OK).hasForwardedUrl("index.html");
		assertIndexHtml(this.mvc.get().uri("/index.html").exchange());
	}

	@Test
	void 画面のURLを直接開いてもログインしていなくてもindexHtmlを返す() {
		for (String url : new String[] { "/users/taro_y", "/users/taro_y/followers", "/books/56", "/reviews/12",
				"/books/search?q=taro", "/settings/profile", "/login" }) {
			assertIndexHtml(this.mvc.get().uri(url).exchange());
		}
	}

	@Test
	void assetsのファイルは1年キャッシュさせる() {
		MvcTestResult result = this.mvc.get().uri("/assets/index-test1234.js").exchange();
		assertThat(result).hasStatus(HttpStatus.OK).bodyText().contains("test-asset-js");
		assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable");
	}

	@Test
	void ないファイルは404でindexHtmlを返さない() {
		assertThat(this.mvc.get().uri("/assets/nothing.js").exchange()).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.mvc.get().uri("/favicon.ico").exchange()).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.mvc.get().uri("/users/taro.png").exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void ないAPIは今までどおりJSONの404でindexHtmlを返さない() {
		MvcTestResult result = this.mvc.get().uri("/api/nothing").with(loggedIn(1)).exchange();
		assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
		// ログインしていなければ 401（API は今までどおり守る）
		assertThat(this.mvc.get().uri("/api/nothing").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.get().uri("/api").with(loggedIn(1)).exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void actuatorのないパスは404でhealthはそのまま() {
		assertThat(this.mvc.get().uri("/actuator/nothing").exchange()).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.mvc.get().uri("/actuator/health").exchange()).hasStatus(HttpStatus.OK)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("UP");
	}

	private static void assertIndexHtml(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.OK).bodyText().contains("test-index-page");
		assertThat(result).headers().hasValue(HttpHeaders.CACHE_CONTROL, "no-cache");
	}

}
