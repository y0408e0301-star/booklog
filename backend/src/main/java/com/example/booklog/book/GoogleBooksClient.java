package com.example.booklog.book;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

import tools.jackson.databind.JsonNode;

// Google Books API（v1）を呼ぶ（FN-02 3 章）。呼ぶだけで、使い回しと回数は GoogleBooksCatalog が受け持つ。
// 止まっている・エラーを返した・時間切れのときは GoogleBooksUnavailableException を投げる（B-8）
@Component
class GoogleBooksClient {

	// 1 ページの件数（B-3）
	static final int PAGE_SIZE = 20;

	private final RestClient restClient;

	private final GoogleBooksProperties properties;

	GoogleBooksClient(RestClient googleBooksRestClient, GoogleBooksProperties properties) {
		this.restClient = googleBooksRestClient;
		this.properties = properties;
	}

	// 検索（GET /volumes?q=）。Google Books API が返した順に、20 件までを返す（5 章の 2）
	List<BookData> search(BookQuery query, int page) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("q", query.googleQuery());
		JsonNode body = call(() -> this.restClient.get().uri((builder) -> {
			builder.path("/volumes")
				.queryParam("q", "{q}")
				.queryParam("startIndex", (page - 1) * PAGE_SIZE)
				.queryParam("maxResults", PAGE_SIZE);
			// 書名・著者名のときは本だけにする（雑誌などを除く）。ISBN のときは 1 冊に決まるので付けない（5 章の 1）
			if (!query.isbn()) {
				builder.queryParam("printType", "books");
			}
			return withKey(builder, variables);
		}).retrieve().body(JsonNode.class));
		List<BookData> books = new ArrayList<>();
		for (JsonNode volume : body.path("items")) {
			books.add(GoogleBooksVolumes.toBookData(volume));
		}
		return books;
	}

	// 1 冊の情報（GET /volumes/{id}）。Google Books にない（消された）ときは空
	Optional<BookData> volume(String googleBooksId) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("id", googleBooksId);
		try {
			JsonNode body = call(() -> this.restClient.get()
				.uri((builder) -> withKey(builder.path("/volumes/{id}"), variables))
				.retrieve()
				.body(JsonNode.class));
			return Optional.of(GoogleBooksVolumes.toBookData(body));
		}
		catch (GoogleBooksUnavailableException ex) {
			// 存在しない ID は 404、形の違う ID は 400 が返る
			if (ex.status() == HttpStatus.NOT_FOUND.value() || ex.status() == HttpStatus.BAD_REQUEST.value()) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	// API キーは URL の key= で送る。値は {key} の変数で渡し、URL のエンコードを任せる
	private URI withKey(UriBuilder builder, Map<String, Object> variables) {
		if (this.properties.hasApiKey()) {
			builder.queryParam("key", "{key}");
			variables.put("key", this.properties.apiKey());
		}
		return builder.build(variables);
	}

	private static JsonNode call(Supplier<JsonNode> request) {
		try {
			JsonNode body = request.get();
			if (body == null) {
				throw new GoogleBooksUnavailableException(HttpStatus.OK.value(), "本文がありません");
			}
			return body;
		}
		catch (RestClientResponseException ex) {
			// 500・503（止まっている）、429・403（回数の上限）など
			throw new GoogleBooksUnavailableException(ex.getStatusCode().value(), ex.getStatusText());
		}
		catch (ResourceAccessException ex) {
			// つながらない、5 秒で応答がない
			throw new GoogleBooksUnavailableException(0, ex.getClass().getSimpleName());
		}
		catch (RestClientException ex) {
			// JSON でない本文など
			throw new GoogleBooksUnavailableException(HttpStatus.OK.value(), ex.getClass().getSimpleName());
		}
	}

	// Google Books API 専用の RestClient。接続と応答の待ち時間に上限を付ける（B-8）
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(GoogleBooksProperties.class)
	static class Config {

		@Bean
		RestClient googleBooksRestClient(RestClient.Builder builder, GoogleBooksProperties properties) {
			HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
			JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
			requestFactory.setReadTimeout(properties.timeout());
			return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
		}

	}

}
