package com.example.booklog.common;

import org.jspecify.annotations.Nullable;

// エラーのときに返す JSON（機能定義書 3.3）。field があれば、画面はその入力欄の下にメッセージを出す
// {"code":"USERNAME_TAKEN","message":"このユーザー ID は使われています。","field":"username"}
public record ErrorResponse(String code, String message, @Nullable String field) {

	public static ErrorResponse of(ErrorCode code, @Nullable String field) {
		return new ErrorResponse(code.name(), code.message(), field);
	}

}
