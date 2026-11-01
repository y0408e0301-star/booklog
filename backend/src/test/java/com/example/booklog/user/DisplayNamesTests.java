package com.example.booklog.user;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.booklog.common.ErrorCode;

// 表示名の条件（FN-01 A-4、FN-11 PF-7）
class DisplayNamesTests {

	@Test
	void 前後の空白を取り除いて返す() {
		assertThat(DisplayNames.valid("　たろう ")).isEqualTo("たろう");
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", " ", "　", "\t\n" })
	void 空か空白だけならDISPLAY_NAME_REQUIRED(String raw) {
		assertApiError(() -> DisplayNames.valid(raw), ErrorCode.DISPLAY_NAME_REQUIRED, "displayName");
	}

	@Test
	void 一文字から使える() {
		assertThat(DisplayNames.valid("a")).isEqualTo("a");
	}

	@Test
	void 三十文字までで三十一文字はBAD_REQUEST() {
		assertThat(DisplayNames.valid("あ".repeat(30))).hasSize(30);
		assertApiError(() -> DisplayNames.valid("あ".repeat(31)), ErrorCode.BAD_REQUEST, "displayName");
	}

	@Test
	void 前後の空白は文字数に入れない() {
		assertThat(DisplayNames.valid(" " + "あ".repeat(30) + " ")).hasSize(30);
	}

	@Test
	void 絵文字は1文字として数える() {
		assertThat(DisplayNames.valid("😀".repeat(30))).hasSize(60);
		assertApiError(() -> DisplayNames.valid("😀".repeat(31)), ErrorCode.BAD_REQUEST, "displayName");
	}

}
