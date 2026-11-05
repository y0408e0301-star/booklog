package com.example.booklog.user;

import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 自己紹介の条件（FN-11 PF-7・4 章）
public final class Bios {

	// users.bio の varchar(160) と同じ
	public static final int MAX = 160;

	private static final Pattern LINE_BREAKS = Pattern.compile("\r\n?");

	private Bios() {
	}

	// 改行を LF にそろえ、前後の空白を取り除いて 0〜160 文字（コードポイント）。
	// 画面では 161 文字目を入力できないため、超えるのは画面を通さずに送られたときだけ
	public static String valid(@Nullable String raw) {
		if (raw == null) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "bio");
		}
		String bio = LINE_BREAKS.matcher(raw).replaceAll("\n").strip();
		if (bio.codePointCount(0, bio.length()) > MAX) {
			throw new ApiException(ErrorCode.BAD_REQUEST, "bio");
		}
		return bio;
	}

}
