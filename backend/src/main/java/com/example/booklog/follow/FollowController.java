package com.example.booklog.follow;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

// フォローの API（機能定義書 FN-07 6 章）
@Tag(name = "FN-07 フォロー")
@RestController
@RequestMapping("/api/users/{username}")
class FollowController {

	private final FollowService followService;

	FollowController(FollowService followService) {
		this.followService = followService;
	}

	@Operation(operationId = "follow", summary = "フォローする", description = "何回送っても同じ結果になる。自分はフォローできない（400）")
	@PutMapping("/follow")
	FollowService.FollowResult follow(@AuthenticationPrincipal Jwt jwt, @PathVariable String username) {
		return this.followService.follow(userId(jwt), username);
	}

	@Operation(operationId = "unfollow", summary = "フォローを解除する", description = "何回送っても同じ結果になる")
	@DeleteMapping("/follow")
	FollowService.FollowResult unfollow(@AuthenticationPrincipal Jwt jwt, @PathVariable String username) {
		return this.followService.unfollow(userId(jwt), username);
	}

	@Operation(operationId = "listFollowing", summary = "フォロー中の一覧", description = "新しくフォローした順に 20 件ずつ")
	@GetMapping("/following")
	FollowService.UserPage following(@AuthenticationPrincipal Jwt jwt, @PathVariable String username,
			@Parameter(description = "前回の nextCursor。最初は省く") @RequestParam(required = false) @Nullable String cursor) {
		return this.followService.following(userId(jwt), username, cursor);
	}

	@Operation(operationId = "listFollowers", summary = "フォロワーの一覧", description = "新しくフォローされた順に 20 件ずつ")
	@GetMapping("/followers")
	FollowService.UserPage followers(@AuthenticationPrincipal Jwt jwt, @PathVariable String username,
			@Parameter(description = "前回の nextCursor。最初は省く") @RequestParam(required = false) @Nullable String cursor) {
		return this.followService.followers(userId(jwt), username, cursor);
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
