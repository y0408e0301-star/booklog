package com.example.booklog.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 画面の URL なら index.html を返し、API・ないファイルは 404 のままにする
class AppRouteResolverTests {

	@ParameterizedTest
	@ValueSource(strings = { "", "login", "users/taro_y", "users/taro_y/following", "books/56", "books/search",
			"reviews/12", "settings/profile", "apiary", "users/api" })
	void 画面のURL(String path) {
		assertThat(FrontendConfig.AppRouteResolver.isAppRoute(path)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "api", "api/books", "api/unknown", "actuator", "actuator/health", "favicon.ico",
			"assets/index-1234.js", "users/taro.y", "robots.txt" })
	void 画面のURLではない(String path) {
		assertThat(FrontendConfig.AppRouteResolver.isAppRoute(path)).isFalse();
	}

}
