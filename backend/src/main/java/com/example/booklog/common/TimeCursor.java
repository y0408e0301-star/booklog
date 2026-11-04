package com.example.booklog.common;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jspecify.annotations.Nullable;

// 「日時と ID」で並べる一覧の続きの位置。最後に出した行の日時（マイクロ秒）と ID（本棚 FN-08 5 章の 5、フォロー FN-07 5 章の 3）。
// 画面は中身を読まずにそのまま送り返す（機能定義書 3.4）
public record TimeCursor(Instant at, long id) {

	// 「1730700000123456_56」の形。なければ null。形が違えば 400
	public static @Nullable TimeCursor parse(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String[] parts = value.split("_", -1);
		try {
			if (parts.length == 2) {
				long micros = Long.parseLong(parts[0]);
				return new TimeCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), Long.parseLong(parts[1]));
			}
		}
		catch (NumberFormatException | ArithmeticException | DateTimeException ex) {
			// 下で 400 にする
		}
		throw new ApiException(ErrorCode.BAD_REQUEST, "cursor");
	}

	public String format() {
		return ChronoUnit.MICROS.between(Instant.EPOCH, this.at) + "_" + this.id;
	}

}
