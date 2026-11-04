package com.example.booklog.user;

// 書いた人（機能定義書 3.5）。レビューとコメントで使う
public record Author(String username, String displayName) {

	public static Author of(User user) {
		return new Author(user.getUsername(), user.getDisplayName());
	}

}
