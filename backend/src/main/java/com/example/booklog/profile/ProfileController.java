package com.example.booklog.profile;

import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

// プロフィールの API（機能定義書 FN-11 6 章）。その人のレビューの一覧は ReviewController
@Tag(name = "FN-11 プロフィール")
@RestController
class ProfileController {

	private final ProfileService profileService;

	ProfileController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@Operation(operationId = "getProfile", summary = "プロフィール", description = "件数は開いたときに数える")
	@GetMapping("/api/users/{username}")
	ProfileView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String username) {
		return this.profileService.get(userId(jwt), username);
	}

	@Operation(operationId = "editProfile", summary = "表示名と自己紹介を変える", description = "変えられるのはログインした人のプロフィールだけ")
	@PatchMapping("/api/me/profile")
	ProfileView edit(@AuthenticationPrincipal Jwt jwt, @RequestBody EditProfileRequest request) {
		return this.profileService.edit(userId(jwt), request.displayName(), request.bio());
	}

	record EditProfileRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String displayName,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable String bio) {
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
