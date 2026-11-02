package com.example.booklog.book;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 24 時間を過ぎた検索の結果（BR-12）と、90 日より前の呼び出し回数（BR-13）を 1 日 1 回まとめて消す
@Component
class BookSearchCleanup {

	// 呼び出し回数を残す日数（BR-13）
	static final Duration USAGE_RETENTION = Duration.ofDays(90);

	private static final Logger log = LoggerFactory.getLogger(BookSearchCleanup.class);

	private final BookSearchCache cache;

	private final GoogleBooksUsage usage;

	private final Clock clock;

	BookSearchCleanup(BookSearchCache cache, GoogleBooksUsage usage, Clock clock) {
		this.cache = cache;
		this.usage = usage;
		this.clock = clock;
	}

	// 毎日 4:30（日本時間。データ設計書 9 章）。使う人が少ない時間に動かす
	@Scheduled(cron = "0 30 4 * * *", zone = "Asia/Tokyo")
	public void deleteOld() {
		Instant now = this.clock.instant();
		int results = this.cache.deleteFetchedBefore(now.minus(GoogleBooksCatalog.CACHE_DURATION));
		LocalDate today = LocalDate.ofInstant(now, GoogleBooksCatalog.ZONE);
		int usages = this.usage.deleteBefore(today.minusDays(USAGE_RETENTION.toDays()));
		log.atInfo()
			.addKeyValue("deleted.count", results + usages)
			.log("古い書籍検索の結果を {} 件、呼び出し回数を {} 日分消しました", results, usages);
	}

}
