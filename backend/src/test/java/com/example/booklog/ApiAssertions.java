package com.example.booklog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 機能定義書のエラー（ApiException）が、決めた code と field で投げられたかを確かめる
public final class ApiAssertions {

	private ApiAssertions() {
	}

	public static void assertApiError(ThrowingCallable call, ErrorCode code) {
		assertApiError(call, code, null);
	}

	public static void assertApiError(ThrowingCallable call, ErrorCode code, @Nullable String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, (ex) -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.field()).isEqualTo(field);
		});
	}

}
