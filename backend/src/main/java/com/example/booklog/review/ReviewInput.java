package com.example.booklog.review;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 確かめた後の、レビューの星・感想・ネタバレあり（FN-03 4 章）
public record ReviewInput(int rating, String body, boolean spoiler) {

	// 感想の文字数の上限（RV-4）。reviews.body の varchar(2000) と同じ
	static final int BODY_MAX = 2000;

	private static final Pattern LINE_BREAKS = Pattern.compile("\r\n?");

	// 星は 1〜5、感想は改行を LF にそろえてトリムした後で 1〜2,000 文字（コードポイント）。ネタバレありは省いたら false
	static ReviewInput of(@Nullable Integer rating, @Nullable String rawBody, @Nullable Boolean spoiler) {
		if (rating == null) {
			throw new ApiException(ErrorCode.RATING_REQUIRED, "rating");
		}
		if (rating < 1 || rating > 5) {
			// 画面では 1〜5 しか選べない
			throw new ApiException(ErrorCode.BAD_REQUEST, "rating");
		}
		String body = (rawBody == null) ? "" : LINE_BREAKS.matcher(rawBody).replaceAll("\n").strip();
		if (body.isEmpty()) {
			throw new ApiException(ErrorCode.BODY_REQUIRED, "body");
		}
		if (body.codePointCount(0, body.length()) > BODY_MAX) {
			// 画面では 2,000 文字を超えると送れない
			throw new ApiException(ErrorCode.BAD_REQUEST, "body");
		}
		return new ReviewInput(rating, body, spoiler != null && spoiler);
	}

}
