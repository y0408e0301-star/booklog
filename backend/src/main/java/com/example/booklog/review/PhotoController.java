package com.example.booklog.review;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

// 写真の URL の取り直し（機能定義書 FN-04 6 章、PH-8）。写真はログインしている人なら誰でも見られる（8 章）
@Tag(name = "FN-04 写真")
@RestController
class PhotoController {

	static final int IDS_MAX = 50;

	private final ReviewViews views;

	PhotoController(ReviewViews views) {
		this.views = views;
	}

	// 削除された写真の ID は、エラーにせず結果に入れない
	@Operation(operationId = "refreshPhotoUrls", summary = "写真の署名付き URL を取り直す", description = "削除された写真は結果に入らない")
	@PostMapping("/api/photos/urls")
	@Transactional(readOnly = true)
	UrlsResponse urls(@RequestBody UrlsRequest request) {
		List<Long> ids = request.photoIds();
		if (ids == null || ids.size() > IDS_MAX) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "photoIds");
		}
		return new UrlsResponse(this.views.photos(ids));
	}

	record UrlsRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
			description = "写真の ID。50 個まで") @Nullable List<Long> photoIds) {
	}

	record UrlsResponse(List<ReviewView.Photo> items) {

		UrlsResponse {
			items = List.copyOf(items);
		}

	}

}
