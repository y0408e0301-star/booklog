package com.example.booklog.helpful;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 参考になったの API の入口（FN-05 6 章）：URL・ステータス・JSON の形。処理の中身は HelpfulServiceTests
@ControllerTest(HelpfulController.class)
class HelpfulControllerTests {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	HelpfulService helpfulService;

	@Test
	void 付けると押した状態と件数を返す() {
		when(this.helpfulService.mark(1, 7)).thenReturn(new HelpfulService.HelpfulResult(true, 13));
		assertThat(this.mvc.put().uri("/api/reviews/7/helpful").with(loggedIn(1)).with(csrf())).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"helpful\":true,\"helpfulCount\":13}");
	}

	@Test
	void 取り消すと押していない状態と件数を返す() {
		when(this.helpfulService.unmark(1, 7)).thenReturn(new HelpfulService.HelpfulResult(false, 12));
		assertThat(this.mvc.delete().uri("/api/reviews/7/helpful").with(loggedIn(1)).with(csrf())).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"helpful\":false,\"helpfulCount\":12}");
	}

	@Test
	void ログインとCSRFトークンが要る() {
		assertThat(this.mvc.put().uri("/api/reviews/7/helpful").with(csrf())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.delete().uri("/api/reviews/7/helpful").with(loggedIn(1))).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.helpfulService);
	}

	// TC-05-3
	@Test
	void 自分のレビューは403() {
		when(this.helpfulService.mark(1, 7)).thenThrow(new ApiException(ErrorCode.CANNOT_MARK_OWN_REVIEW));
		assertError(this.mvc.put().uri("/api/reviews/7/helpful").with(loggedIn(1)).with(csrf()).exchange(),
				ErrorCode.CANNOT_MARK_OWN_REVIEW);
	}

}
