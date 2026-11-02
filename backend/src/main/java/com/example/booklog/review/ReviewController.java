package com.example.booklog.review;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.tags.Tag;

// レビューの API（機能定義書 FN-03 6 章）と、レビューの一覧（本のページ FN-10 6 章、タイムライン FN-09 6 章）。
// 写真（FN-04。開発計画書 1-6）と一緒に送れるよう、投稿と編集は multipart で受け取る。
// ファイルの項目がない multipart の項目を springdoc はクエリとして書くので、仕様書には ReviewForm を本文として書き、
// 項目そのものは仕様書から隠す（写真の項目を足したら見直す）
@RestController
class ReviewController {

	private final ReviewService reviewService;

	ReviewController(ReviewService reviewService) {
		this.reviewService = reviewService;
	}

	@Tag(name = "FN-03 レビュー")
	@Operation(operationId = "createReview", summary = "レビューを投稿する",
			description = "1 人 1 冊に 1 つ（もうあれば 409）。その本が本棚になければ「読了」で入れる")
	@RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
			schema = @Schema(implementation = ReviewForm.class)))
	@PostMapping(path = "/api/books/{bookId}/reviews", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	ReviewView create(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId,
			@Parameter(hidden = true) @RequestParam(required = false) @Nullable Integer rating,
			@Parameter(hidden = true) @RequestParam(required = false) @Nullable String body,
			@Parameter(hidden = true) @RequestParam(required = false) @Nullable Boolean spoiler) {
		return this.reviewService.create(userId(jwt), bookId, ReviewInput.of(rating, body, spoiler));
	}

	@Tag(name = "FN-03 レビュー")
	@Operation(operationId = "getReview", summary = "レビューの詳細")
	@GetMapping("/api/reviews/{id}")
	ReviewView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return this.reviewService.get(userId(jwt), id);
	}

	@Tag(name = "FN-03 レビュー")
	@Operation(operationId = "editReview", summary = "レビューを編集する", description = "自分のレビューだけ。投稿日時・並び順・参考になった・コメントは変わらない")
	@RequestBody(content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
			schema = @Schema(implementation = ReviewForm.class)))
	@PatchMapping(path = "/api/reviews/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ReviewView edit(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
			@Parameter(hidden = true) @RequestParam(required = false) @Nullable Integer rating,
			@Parameter(hidden = true) @RequestParam(required = false) @Nullable String body,
			@Parameter(hidden = true) @RequestParam(required = false) @Nullable Boolean spoiler) {
		return this.reviewService.edit(userId(jwt), id, ReviewInput.of(rating, body, spoiler));
	}

	@Tag(name = "FN-03 レビュー")
	@Operation(operationId = "deleteReview", summary = "レビューを削除する", description = "自分のレビューだけ。写真・コメント・参考になったも消え、本と本棚は残る")
	@DeleteMapping("/api/reviews/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		this.reviewService.delete(userId(jwt), id);
	}

	@Tag(name = "FN-10 本のページ")
	@Operation(operationId = "listBookReviews", summary = "本のレビューの一覧", description = "新しい順に 20 件ずつ")
	@GetMapping("/api/books/{bookId}/reviews")
	ReviewService.ReviewPage listByBook(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId,
			@Parameter(description = "前回の nextCursor。最初は省く") @RequestParam(required = false) @Nullable String cursor) {
		return this.reviewService.listByBook(userId(jwt), bookId, cursor);
	}

	// 投稿・編集の multipart の項目（仕様書に書くためだけのもの。FN-03 4 章）
	record ReviewForm(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "星。1〜5 の整数（RV-3）") @Nullable Integer rating,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
					description = "感想。改行を LF にそろえ、前後の空白を除いて 1〜2,000 文字（RV-4）") @Nullable String body,
			@Schema(description = "ネタバレあり。省いたら false（RV-11）") @Nullable Boolean spoiler) {
	}

	@Tag(name = "FN-09 タイムライン")
	@Operation(operationId = "readTimeline", summary = "タイムライン",
			description = "following は自分とフォローしている人、all は全員のレビュー。新しい順に 20 件ずつ")
	@GetMapping("/api/timeline")
	ReviewService.ReviewPage timeline(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "following か all（T-1）",
					schema = @Schema(allowableValues = { "following", "all" })) @RequestParam(
							required = false) @Nullable String tab,
			@Parameter(description = "前回の nextCursor。最初は省く") @RequestParam(required = false) @Nullable String cursor) {
		return this.reviewService.timeline(userId(jwt), tab, cursor);
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
