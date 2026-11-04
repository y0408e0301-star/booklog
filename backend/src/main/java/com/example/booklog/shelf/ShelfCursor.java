package com.example.booklog.shelf;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 本棚の続きの位置。最後に出した行の updated_at（マイクロ秒）と本の ID（FN-08 5 章の 5）。
// 画面は中身を読まずにそのまま送り返す（機能定義書 3.4）
record ShelfCursor(Instant updatedAt, long bookId) {

	static ShelfCursor after(ReadingStatuses.ShelfItem item) {
		return new ShelfCursor(item.updatedAt(), item.book().id());
	}

	// 「1730700000123456_56」の形
	static @Nullable ShelfCursor parse(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.split("_", -1);
		try {
			if (parts.length == 2) {
				long micros = Long.parseLong(parts[0]);
				return new ShelfCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), Long.parseLong(parts[1]));
			}
		}
		catch (NumberFormatException | ArithmeticException | DateTimeException ex) {
			// 下で 400 にする
		}
		throw new ApiException(ErrorCode.BAD_REQUEST, "cursor");
	}

	String format() {
		return ChronoUnit.MICROS.between(Instant.EPOCH, this.updatedAt) + "_" + this.bookId;
	}

}
