package com.example.booklog.shelf;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.booklog.common.ErrorCode;

// 読書ステータスの文字の読み方（FN-08 4 章）
class ReadingStatusTests {

	@Test
	void ステータスは3つの名前だけ() {
		assertThat(ReadingStatus.parse("WANT")).isEqualTo(ReadingStatus.WANT);
		assertThat(ReadingStatus.parse("READING")).isEqualTo(ReadingStatus.READING);
		assertThat(ReadingStatus.parse("READ")).isEqualTo(ReadingStatus.READ);
		for (String value : new String[] { null, "", "read", "DONE" }) {
			assertApiError(() -> ReadingStatus.parse(value), ErrorCode.BAD_REQUEST, "status");
		}
	}

}
