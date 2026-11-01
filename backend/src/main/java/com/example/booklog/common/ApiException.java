package com.example.booklog.common;

import org.jspecify.annotations.Nullable;

// 機能定義書で決めたエラーを返すための例外。ApiExceptionHandler が ErrorResponse にする
public class ApiException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final ErrorCode code;

	private final @Nullable String field;

	public ApiException(ErrorCode code) {
		this(code, null);
	}

	public ApiException(ErrorCode code, @Nullable String field) {
		super(code.name());
		this.code = code;
		this.field = field;
	}

	public ErrorCode code() {
		return this.code;
	}

	public @Nullable String field() {
		return this.field;
	}

}
