package com.example.booklog.shelf;

import org.jspecify.annotations.Nullable;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 読書ステータス（FN-08 4 章）。reading_statuses.status にこの名前のまま入れる
public enum ReadingStatus {

	// 読みたい
	WANT,

	// 読書中
	READING,

	// 読了
	READ;

	// API で受け取った文字を読む。なければ・3 つのどれでもなければ 400
	static ReadingStatus parse(@Nullable String value) {
		if (value != null) {
			for (ReadingStatus status : values()) {
				if (status.name().equals(value)) {
					return status;
				}
			}
		}
		throw new ApiException(ErrorCode.BAD_REQUEST, "status");
	}

}
