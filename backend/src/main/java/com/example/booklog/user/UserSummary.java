package com.example.booklog.user;

// 一覧に出すユーザー（機能定義書 3.5 UserSummary）。bioFirstLine は自己紹介の最初の改行の前まで
// （60 文字を超えるときは画面で「…」で切る。FN-11 5 章の 4）。me は見ている人自身か
public record UserSummary(String username, String displayName, String bioFirstLine, boolean followedByMe, boolean me) {

	public static UserSummary of(User user, long viewerId, boolean followedByMe) {
		String bio = user.getBio();
		int lineBreak = bio.indexOf('\n');
		return new UserSummary(user.getUsername(), user.getDisplayName(),
				(lineBreak < 0) ? bio : bio.substring(0, lineBreak), followedByMe, user.getId() == viewerId);
	}

}
