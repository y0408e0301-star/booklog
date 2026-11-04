package com.example.booklog.helpful;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

// 参考になったの API（機能定義書 FN-05 6 章）
@Tag(name = "FN-05 参考になった")
@RestController
class HelpfulController {

	private final HelpfulService helpfulService;

	HelpfulController(HelpfulService helpfulService) {
		this.helpfulService = helpfulService;
	}

	@Operation(operationId = "markHelpful", summary = "参考になったを付ける", description = "何回送っても同じ結果になる。自分のレビューには付けられない（403）")
	@PutMapping("/api/reviews/{id}/helpful")
	HelpfulService.HelpfulResult mark(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return this.helpfulService.mark(userId(jwt), id);
	}

	@Operation(operationId = "unmarkHelpful", summary = "参考になったを取り消す", description = "何回送っても同じ結果になる")
	@DeleteMapping("/api/reviews/{id}/helpful")
	HelpfulService.HelpfulResult unmark(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return this.helpfulService.unmark(userId(jwt), id);
	}

	// JWT の sub にはユーザーの ID（users.id）が入っている（TokenService）
	private static long userId(Jwt jwt) {
		return Long.parseLong(jwt.getSubject());
	}

}
