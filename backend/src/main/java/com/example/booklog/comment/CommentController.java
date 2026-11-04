package com.example.booklog.comment;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

// コメントの API（機能定義書 FN-06 6 章）
@Tag(name = "FN-06 コメント")
@RestController
class CommentController {

	private final CommentService commentService;

	CommentController(CommentService commentService) {
		this.commentService = commentService;
	}

	@Operation(operationId = "listComments", summary = "コメントの一覧", description = "古い順に 50 件ずつ")
	@GetMapping("/api/reviews/{reviewId}/comments")
	CommentService.CommentPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable long reviewId,
			@Parameter(description = "前回の nextCursor。最初は省く") @RequestParam(required = false) @Nullable String cursor) {
		return this.commentService.list(userId(jwt), reviewId, cursor);
	}

	@Operation(operationId = "createComment", summary = "コメントする", description = "自分のレビューにも付けられる")
	@PostMapping("/api/reviews/{reviewId}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	CommentView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long reviewId,
			@RequestBody CreateCommentRequest request) {
		return this.commentService.create(userId(jwt), reviewId, request.body());
	}

	@Operation(operationId = "deleteComment", summary = "コメントを削除する",
			description = "自分のコメントだけ。レビューを書いた人も、ほかの人のコメントは消せない")
	@DeleteMapping("/api/comments/{commentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long commentId) {
		this.commentService.delete(userId(jwt), commentId);
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

	record CreateCommentRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
			description = "改行を LF にそろえ、前後の空白を除いて 1〜500 文字（C-2）") @Nullable String body) {
	}

}
