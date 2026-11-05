package com.example.booklog.profile;

import com.example.booklog.shelf.BookshelfCounts;

// プロフィール（FN-11 6 章）。me は見ている人自身か
record ProfileView(String username, String displayName, String bio, long reviewCount, long followingCount,
		long followerCount, BookshelfCounts bookshelfCounts, boolean followedByMe, boolean me) {
}
