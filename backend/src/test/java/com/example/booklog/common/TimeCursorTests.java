package com.example.booklog.common;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

// 「日時と ID」で並べる一覧の続きの位置（FN-07 5 章の 3、FN-08 5 章の 5）
class TimeCursorTests {

	@Test
	void 日時はマイクロ秒まで持ち読み直すと同じになる() {
		TimeCursor cursor = new TimeCursor(Instant.parse("2026-11-04T12:34:56.123456Z"), 56);
		assertThat(cursor.format()).isEqualTo("1793795696123456_56");
		assertThat(TimeCursor.parse(cursor.format())).isEqualTo(cursor);
	}

	@Test
	void なければnull() {
		assertThat(TimeCursor.parse(null)).isNull();
	}

	@Test
	void 形が違えば400() {
		for (String value : new String[] { "", "56", "a_56", "1_b", "1_2_3", "_56", "99999999999999999999_1" }) {
			assertApiError(() -> TimeCursor.parse(value), ErrorCode.BAD_REQUEST, "cursor");
		}
	}

}
