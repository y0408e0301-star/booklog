package com.example.booklog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class BackendApplicationTests {

	@Autowired
	MockMvcTester mvc;

	// 公開した後の稼働の確認に使う。ログインしなくても読める
	@Test
	void ヘルスチェックはログインなしでUPを返す() {
		assertThat(this.mvc.get().uri("/actuator/health")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("UP");
	}

}
