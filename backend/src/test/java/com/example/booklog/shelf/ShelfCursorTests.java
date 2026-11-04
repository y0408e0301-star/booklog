package com.example.booklog.shelf;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.booklog.common.ErrorCode;

// 本棚の続きの位置と、読書ステータスの文字の読み方（FN-08 4 章・5 章の 5）
class ShelfCursorTests {

	@Test
	void 日時はマイクロ秒まで持ち読み直すと同じになる() {
		ShelfCursor cursor = new ShelfCursor(Instant.parse("2026-11-04T12:34:56.123456Z"), 56);
		assertThat(cursor.format()).isEqualTo("1793795696123456_56");
		assertThat(ShelfCursor.parse(cursor.format())).isEqualTo(cursor);
	}

	@Test
	void なければnull() {
		assertThat(ShelfCursor.parse(null)).isNull();
	}

	@Test
	void 形が違えば400() {
		for (String value : new String[] { "", "56", "a_56", "1_b", "1_2_3", "_56", "99999999999999999999_1" }) {
			assertApiError(() -> ShelfCursor.parse(value), ErrorCode.BAD_REQUEST, "cursor");
		}
	}

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
