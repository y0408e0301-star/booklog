package com.example.booklog.shelf;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

// 読書ステータスと本棚の API（機能定義書 FN-08 6 章）
@Tag(name = "FN-08 読書ステータス・本棚")
@RestController
class ShelfController {

	private final ShelfService shelfService;

	ShelfController(ShelfService shelfService) {
		this.shelfService = shelfService;
	}

	@Operation(operationId = "setReadingStatus", summary = "読書ステータスを付ける・変える", description = "自分の本棚だけ。同じステータスなら並び順を変えない")
	@PutMapping("/api/books/{bookId}/status")
	StatusResponse set(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId,
			@RequestBody StatusRequest request) {
		return new StatusResponse(this.shelfService.set(userId(jwt), bookId, request.status()));
	}

	@Operation(operationId = "removeReadingStatus", summary = "読書ステータスを外す", description = "自分の本棚から本をなくす。レビューは消さない")
	@DeleteMapping("/api/books/{bookId}/status")
	StatusResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long bookId) {
		this.shelfService.remove(userId(jwt), bookId);
		return new StatusResponse(null);
	}

	@Operation(operationId = "listBookshelf", summary = "本棚の一覧",
			description = "ステータスを付けた（変えた）日時の新しい順に 20 冊ずつ。誰の本棚でも見られる")
	@GetMapping("/api/users/{username}/bookshelf")
	ShelfService.ShelfPage list(@PathVariable String username,
			@Parameter(description = "WANT・READING・READ のどれか（ST-4）",
					schema = @Schema(allowableValues = { "WANT", "READING", "READ" })) @RequestParam(
							required = false) @Nullable String status,
			@Parameter(description = "前回の nextCursor。最初は省く") @RequestParam(required = false) @Nullable String cursor) {
		return this.shelfService.list(username, status, cursor);
	}

	record StatusRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {
			"WANT", "READING", "READ" }) @Nullable String status){
	}

	// 外したときは status が null
	record StatusResponse(@Nullable ReadingStatus status) {
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
