package com.example.booklog.helpful;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// 参考になった（機能定義書 FN-05）。PUT・DELETE は何回送っても同じ結果になる（5 章の 2）
@Service
class HelpfulService {

	private final Helpfuls helpfuls;

	HelpfulService(Helpfuls helpfuls) {
		this.helpfuls = helpfuls;
	}

	// 自分のレビューには付けられない（H-4）。helpfulCount はほかの人の操作も含めた最新の数
	@Transactional
	HelpfulResult mark(long userId, long reviewId) {
		if (authorOf(reviewId) == userId) {
			throw new ApiException(ErrorCode.CANNOT_MARK_OWN_REVIEW);
		}
		try {
			this.helpfuls.add(userId, reviewId);
		}
		catch (DataIntegrityViolationException ex) {
			// 確かめた後でレビューが削除された（外部キーの制約に引っかかった）
			throw new ApiException(ErrorCode.REVIEW_NOT_FOUND);
		}
		return new HelpfulResult(true, this.helpfuls.count(reviewId));
	}

	@Transactional
	HelpfulResult unmark(long userId, long reviewId) {
		authorOf(reviewId);
		this.helpfuls.remove(userId, reviewId);
		return new HelpfulResult(false, this.helpfuls.count(reviewId));
	}

	// レビューを書いた人。レビューがなければ 404（M-20）
	private long authorOf(long reviewId) {
		return this.helpfuls.authorOf(reviewId).orElseThrow(() -> new ApiException(ErrorCode.REVIEW_NOT_FOUND));
	}

	// 付ける・取り消すの結果（FN-05 6 章）
	record HelpfulResult(boolean helpful, long helpfulCount) {
	}

}
