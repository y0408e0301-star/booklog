package com.example.booklog.auth;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 有効期限を過ぎたリフレッシュトークンを 1 日 1 回まとめて消す（BR-9、機能定義書 FN-01 7 章）
@Component
class RefreshTokenCleanup {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

	private final RefreshTokenRepository refreshTokens;

	private final Clock clock;

	RefreshTokenCleanup(RefreshTokenRepository refreshTokens, Clock clock) {
		this.refreshTokens = refreshTokens;
		this.clock = clock;
	}

	// 毎日 4:00（日本時間）。使う人が少ない時間に動かす
	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
	@Transactional
	public void deleteExpired() {
		int deleted = this.refreshTokens.deleteExpired(this.clock.instant());
		log.atInfo().addKeyValue("deleted.count", deleted).log("期限切れのリフレッシュトークンを {} 件消しました", deleted);
	}

}
