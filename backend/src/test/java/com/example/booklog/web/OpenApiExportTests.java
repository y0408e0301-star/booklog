package com.example.booklog.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;

// API の仕様書（OpenAPI）を docs/api/openapi.yaml に書き出す（基本設計書 2.1）。
// コミットしたファイルと違えば、書き直したうえで失敗させる。API を変えたのに仕様書を作り直していない Pull Request は CI で止まる。
// 手元で失敗したら、書き直された openapi.yaml（と画面の型。frontend で npm run gen:api）をコミットする
@SpringBootTest
@ActiveProfiles("dev")
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class OpenApiExportTests {

	// mvnw は backend で実行するので、リポジトリの直下は 1 つ上
	private static final Path SPEC = Path.of("..", "docs", "api", "openapi.yaml");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	OpenApiConfig.RecordNullabilityConverter converter;

	@Test
	void 仕様書がコミットしたopenapiYamlと同じ() throws IOException {
		MvcTestResult result = this.mvc.get().uri("/v3/api-docs.yaml").exchange();
		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(this.converter.conflicts()).as("仕様書の型の名前がぶつかっています。@Schema(name = ...) で分けてください").isEmpty();
		String spec = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
		String committed = Files.exists(SPEC) ? Files.readString(SPEC, StandardCharsets.UTF_8) : "";
		if (!spec.equals(committed)) {
			Files.createDirectories(SPEC.getParent());
			Files.writeString(SPEC, spec, StandardCharsets.UTF_8);
		}
		assertThat(committed).as("API が変わったので docs/api/openapi.yaml を書き直しました。中身を確かめてコミットしてください").isEqualTo(spec);
	}

}
