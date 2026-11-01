package com.example.booklog.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;

// 公開先（dev プロファイルなし）では、API の仕様書と Swagger UI を出さない（基本設計書 2.1）
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class OpenApiDisabledTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void 仕様書を返さない() {
		// 画面の URL と同じ扱いになり、index.html が返る（FrontendConfig）
		assertThat(this.mvc.get().uri("/v3/api-docs").exchange()).bodyText().doesNotContain("\"openapi\"");
		assertThat(this.mvc.get().uri("/v3/api-docs.yaml").exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void SwaggerUiを出さない() {
		assertThat(this.mvc.get().uri("/swagger-ui.html").exchange()).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(this.mvc.get().uri("/swagger-ui/index.html").exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

}
