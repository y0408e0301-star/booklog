package com.example.booklog.web;

import java.io.IOException;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

// 画面（React をビルドしたファイル）を API と同じ Spring Boot から配る。E2E で使う（インフラ設計書 6 章）。
// ファイルは jar を作るとき（mvnw package -Pwith-frontend）に classpath:/static/ に入る。
// 開発中は Vite（5173）が、公開先は CloudFront が画面を配るので、static/ がなくても API はそのまま動く
@Configuration(proxyBeanMethods = false)
class FrontendConfig implements WebMvcConfigurer {

	private static final String LOCATION = "classpath:/static/";

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// Vite がファイル名に中身のハッシュを付けるので、中身が変われば URL も変わる。長くキャッシュしてよい
		registry.addResourceHandler("/assets/**")
			.addResourceLocations(LOCATION + "assets/")
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
		// index.html などは、新しい画面をすぐ読むよう、毎回確かめさせる
		registry.addResourceHandler("/**")
			.addResourceLocations(LOCATION)
			.setCacheControl(CacheControl.noCache())
			.resourceChain(true)
			.addResolver(new AppRouteResolver());
	}

	// 画面の URL（/users/taro・/books/search?q=… など。React Router が決める）を直接開いたり読み直したりしたときも、
	// index.html を返して画面に任せる。ない API（/api/…）・/actuator/…・拡張子のあるパス（ないファイル）は 404 のまま
	static class AppRouteResolver extends PathResourceResolver {

		@Override
		protected @Nullable Resource getResource(String resourcePath, Resource location) throws IOException {
			Resource found = super.getResource(resourcePath, location);
			if (found != null || !isAppRoute(resourcePath)) {
				return found;
			}
			return super.getResource("index.html", location);
		}

		// resourcePath は先頭の「/」を除いたパス（例：users/taro）
		static boolean isAppRoute(String resourcePath) {
			String first = resourcePath.split("/", 2)[0];
			if (first.equals("api") || first.equals("actuator")) {
				return false;
			}
			String last = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
			return !last.contains(".");
		}

	}

}
