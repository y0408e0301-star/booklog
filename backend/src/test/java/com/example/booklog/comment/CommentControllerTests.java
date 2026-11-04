package com.example.booklog.comment;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.example.booklog.ControllerTest;
import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.Author;

// コメントの API の入口（FN-06 6 章）：URL・ステータス・JSON の形。処理の中身は CommentServiceTests
@ControllerTest(CommentController.class)
class CommentControllerTests {

	private static final CommentView VIEW = new CommentView(901, new Author("hanako", "はなこ"), "私も泣きました", false,
			Instant.parse("2026-11-04T03:00:00Z"));

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	CommentService commentService;

	@Test
	void 一覧を返す() {
		when(this.commentService.list(1, 7, "900")).thenReturn(new CommentService.CommentPage(List.of(VIEW), null));
		assertThat(this.mvc.get().uri("/api/reviews/7/comments?cursor=900").with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"items":[{"id":901,"author":{"username":"hanako","displayName":"はなこ"},"body":"私も泣きました",
					  "mine":false,"createdAt":"2026-11-04T03:00:00Z"}],"nextCursor":null}
					""");
	}

	@Test
	void 付けると201でコメントを返す() {
		when(this.commentService.create(1, 7, "私も泣きました")).thenReturn(VIEW);
		assertThat(this.mvc.post()
			.uri("/api/reviews/7/comments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"私も泣きました\"}")
			.with(loggedIn(1))
			.with(csrf())).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.id").isEqualTo(901);
	}

	@Test
	void 消すと204() {
		assertThat(this.mvc.delete().uri("/api/comments/901").with(loggedIn(2)).with(csrf()))
			.hasStatus(HttpStatus.NO_CONTENT);
		verify(this.commentService).delete(2, 901);
	}

	@Test
	void 付けるにはログインとCSRFトークンが要る() {
		assertThat(this.mvc.post()
			.uri("/api/reviews/7/comments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\"a\"}")
			.with(csrf())).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(this.mvc.delete().uri("/api/comments/901").with(loggedIn(2))).hasStatus(HttpStatus.FORBIDDEN);
		verifyNoInteractions(this.commentService);
	}

	// TC-06-3
	@Test
	void ほかの人のコメントは403() {
		doThrow(new ApiException(ErrorCode.FORBIDDEN)).when(this.commentService).delete(1, 901);
		assertError(this.mvc.delete().uri("/api/comments/901").with(loggedIn(1)).with(csrf()).exchange(),
				ErrorCode.FORBIDDEN);
	}

	@Test
	void 本文が空なら400() {
		when(this.commentService.create(1, 7, " ")).thenThrow(new ApiException(ErrorCode.COMMENT_REQUIRED, "body"));
		assertError(this.mvc.post()
			.uri("/api/reviews/7/comments")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\":\" \"}")
			.with(loggedIn(1))
			.with(csrf())
			.exchange(), ErrorCode.COMMENT_REQUIRED, "body");
	}

}
