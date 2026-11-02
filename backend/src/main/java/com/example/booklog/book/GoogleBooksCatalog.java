package com.example.booklog.book;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

import com.example.booklog.common.ApiException;
import com.example.booklog.common.ErrorCode;

// Google Books API で本を探す・取る（FN-02）。
// 検索は同じ問い合わせ・同じページを 24 時間使い回す（B-9、BR-12）。Google Books API を呼ぶ前に今日の回数を 1 増やし、
// 上限に達していたら呼ばない（BR-13）。呼ぶたびに 1 行のログを出す（基本設計書 2.3）
@Component
class GoogleBooksCatalog implements BookCatalog {

	// 検索の結果を使い回す時間（B-9）
	static final Duration CACHE_DURATION = Duration.ofHours(24);

	// その日の回数がこれを超えたら、警告のログにする（基本設計書 2.3）
	static final int WARN_COUNT = 800;

	// 何ページ目まで出すか（B-3。20 件 × 5 ＝ 100 件）
	static final int MAX_PAGE = 5;

	// 呼び出し回数は日本時間の 0 時に 0 に戻す（FN-02 5 章の 8）
	static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

	private static final Logger log = LoggerFactory.getLogger(GoogleBooksCatalog.class);

	private final GoogleBooksClient client;

	private final BookSearchCache cache;

	private final GoogleBooksUsage usage;

	private final Clock clock;

	GoogleBooksCatalog(GoogleBooksClient client, BookSearchCache cache, GoogleBooksUsage usage, Clock clock) {
		this.client = client;
		this.cache = cache;
		this.usage = usage;
		this.clock = clock;
	}

	@Override
	public BookSearchPage search(BookQuery query, int page) {
		Instant now = this.clock.instant();
		Optional<BookSearchPage> cached = this.cache.find(query.cacheKey(), page, now.minus(CACHE_DURATION));
		if (cached.isPresent()) {
			log.atInfo()
				.addKeyValue("google_books.kind", "search")
				.addKeyValue("google_books.cached", true)
				.log("Google Books API の検索の結果を使い回しました");
			return cached.get();
		}
		List<BookData> books = callApi("search", () -> this.client.search(query, page));
		// totalItems は正確でないことがあるため、20 件そろったかで次のページがあるかを決める（5 章の 2）
		boolean hasNext = books.size() == GoogleBooksClient.PAGE_SIZE && page < MAX_PAGE;
		BookSearchPage result = new BookSearchPage(books.stream().map(BookData::toSearchItem).toList(), hasNext);
		this.cache.save(query.cacheKey(), page, result, now);
		return result;
	}

	@Override
	public Optional<BookData> find(String googleBooksId) {
		return callApi("volume", () -> this.client.volume(googleBooksId));
	}

	// 今日の回数を 1 増やしてから呼ぶ。上限に達していたら呼ばない。
	// 呼んだ結果がエラー・時間切れでも、増やした回数は戻さない（Google Books の側でも数えられるため。BR-13）
	private <T> T callApi(String kind, Supplier<T> call) {
		OptionalInt count = this.usage.increment(LocalDate.ofInstant(this.clock.instant(), ZONE));
		if (count.isEmpty()) {
			log.atWarn()
				.addKeyValue("google_books.kind", kind)
				.addKeyValue("google_books.daily_count", GoogleBooksUsage.DAILY_LIMIT)
				.log("Google Books API の今日の呼び出し回数が上限（{} 回）に達しているので、呼びませんでした", GoogleBooksUsage.DAILY_LIMIT);
			throw new ApiException(ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		}
		long start = System.nanoTime();
		try {
			T result = call.get();
			logCall(kind, 200, start, count.getAsInt());
			return result;
		}
		catch (GoogleBooksUnavailableException ex) {
			logCall(kind, ex.status(), start, count.getAsInt());
			throw new ApiException(ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		}
	}

	// API キー・問い合わせの文字は出さない（基本設計書 2.3）。status が 0 はつながらない・時間切れ
	private static void logCall(String kind, int status, long start, int dailyCount) {
		Duration duration = Duration.ofNanos(System.nanoTime() - start);
		Level level = (status != 200 || dailyCount > WARN_COUNT) ? Level.WARN : Level.INFO;
		log.atLevel(level)
			.addKeyValue("google_books.kind", kind)
			.addKeyValue("google_books.cached", false)
			.addKeyValue("http.response.status_code", status)
			.addKeyValue("event.duration", duration.toNanos())
			.addKeyValue("google_books.daily_count", dailyCount)
			.log("Google Books API {} {} {}ms（今日 {} 回目）", kind, status, duration.toMillis(), dailyCount);
	}

}
