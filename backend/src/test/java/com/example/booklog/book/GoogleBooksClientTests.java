package com.example.booklog.book;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

// Google Books API の呼び方（送る q・startIndex・key）と、返ってきたものの読み方・エラーのときの動き。
// 本物の Google Books API は呼ばず、MockRestServiceServer で応答を決める（技術スタック定義書 3.6、FN-02 5 章の 9）
class GoogleBooksClientTests {

	private static final String BASE_URL = "https://books.test/books/v1";

	private MockRestServiceServer server;

	private GoogleBooksClient client(String apiKey) {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		this.server = MockRestServiceServer.bindTo(builder).build();
		return new GoogleBooksClient(builder.build(),
				new GoogleBooksProperties(BASE_URL, apiKey, Duration.ofSeconds(5)));
	}

	// TC-02-1・TC-02-3
	@Test
	void 書名で探すときは入れた文字とprintTypeとAPIキーを送り2ページ目は20件目から() {
		GoogleBooksClient client = client("secret-key");
		this.server.expect(method(HttpMethod.GET))
			.andExpect(requestTo(startsWith("/volumes")))
			.andExpect(queryParam("q", "%E5%A4%8F%E7%9B%AE%20%E6%BC%B1%E7%9F%B3%2B1"))
			.andExpect(queryParam("startIndex", "20"))
			.andExpect(queryParam("maxResults", "20"))
			.andExpect(queryParam("printType", "books"))
			.andExpect(queryParam("key", "secret-key"))
			.andRespond(withSuccess("""
					{"totalItems":2,"items":[
					  {"id":"a","volumeInfo":{"title":"こころ","authors":["夏目漱石"]}},
					  {"id":"b","volumeInfo":{"title":"それから"}}]}
					""", MediaType.APPLICATION_JSON));

		assertThat(client.search(BookQuery.parse("夏目 漱石+1"), 2)).extracting(BookData::googleBooksId)
			.containsExactly("a", "b");
		this.server.verify();
	}

	// TC-02-1
	@Test
	void ISBNで探すときはprintTypeを付けずAPIキーがなければkeyも付けない() {
		GoogleBooksClient client = client("");
		this.server.expect(requestTo(BASE_URL + "/volumes?q=isbn%3A9784101010014&startIndex=0&maxResults=20"))
			.andRespond(withSuccess("{\"totalItems\":0}", MediaType.APPLICATION_JSON));

		// 0 件のときは items がない
		assertThat(client.search(BookQuery.parse("978-4-10-101001-4"), 1)).isEmpty();
		this.server.verify();
	}

	@Test
	void 一冊の情報を取る() {
		GoogleBooksClient client = client("secret-key");
		this.server.expect(requestTo(BASE_URL + "/volumes/zyTCAlFPjgYC?key=secret-key"))
			.andRespond(withSuccess("{\"id\":\"zyTCAlFPjgYC\",\"volumeInfo\":{\"title\":\"吾輩は猫である\"}}",
					MediaType.APPLICATION_JSON));

		assertThat(client.volume("zyTCAlFPjgYC")).get().extracting(BookData::title).isEqualTo("吾輩は猫である");
	}

	@Test
	void 一冊の情報がないときは空() {
		GoogleBooksClient client = client("");
		this.server.expect(requestTo(BASE_URL + "/volumes/gone")).andRespond(withResourceNotFound());
		assertThat(client.volume("gone")).isEmpty();
	}

	// TC-02-6
	@Test
	void 五百を返したら使えないものとする() {
		GoogleBooksClient client = client("");
		this.server.expect(requestTo(startsWith("/volumes"))).andRespond(withServerError());
		assertUnavailable(() -> client.search(BookQuery.parse("猫"), 1), 500);
	}

	// TC-02-6：回数の上限（Google Books の側）
	@Test
	void 四百二十九を返したら使えないものとする() {
		GoogleBooksClient client = client("");
		this.server.expect(requestTo(startsWith("/volumes"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
		assertUnavailable(() -> client.volume("a"), 429);
	}

	// TC-02-6：5 秒で応答がない
	@Test
	void 時間切れなら使えないものとする() {
		GoogleBooksClient client = client("");
		this.server.expect(requestTo(startsWith("/volumes")))
			.andRespond(withException(new SocketTimeoutException("Read timed out")));
		assertUnavailable(() -> client.search(BookQuery.parse("猫"), 1), 0);
	}

	@Test
	void JSONでない応答なら使えないものとする() {
		GoogleBooksClient client = client("");
		this.server.expect(requestTo(startsWith("/volumes")))
			.andRespond(withSuccess("<html>メンテナンス中</html>", MediaType.TEXT_HTML));
		assertUnavailable(() -> client.search(BookQuery.parse("猫"), 1), 200);
	}

	private static void assertUnavailable(ThrowingCallable call, int status) {
		assertThatThrownBy(call).isInstanceOfSatisfying(GoogleBooksUnavailableException.class,
				(ex) -> assertThat(ex.status()).isEqualTo(status));
	}

	// パス（/volumes など）がこれで始まる URL か。クエリは別に確かめる
	private static Matcher<String> startsWith(String path) {
		return Matchers.startsWith(UriComponentsBuilder.fromUri(URI.create(BASE_URL + path)).toUriString());
	}

}
