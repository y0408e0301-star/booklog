package com.example.booklog.user;

import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 表示名の条件（FN-01 A-4。プロフィールの編集 PF-7 も同じ）
public final class DisplayNames {

	public static final int MAX = 30;

	private DisplayNames() {
	}

	// 前後の空白を取り除いて 1〜30 文字（コードポイント）
	public static String valid(@Nullable String raw) {
		String displayName = (raw == null) ? "" : raw.strip();
		if (displayName.isEmpty()) {
			throw new ApiException(ErrorCode.DISPLAY_NAME_REQUIRED, "displayName");
		}
		// 画面では 31 文字目を入力できないため、超えるのは画面を通さずに送られたときだけ（A-4）
		if (displayName.codePointCount(0, displayName.length()) > MAX) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "displayName");
		}
		return displayName;
	}

}
