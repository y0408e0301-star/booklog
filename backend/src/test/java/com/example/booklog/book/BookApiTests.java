package com.example.booklog.book;

import static com.example.booklog.ControllerTestSupport.assertError;
import static com.example.booklog.ControllerTestSupport.loggedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.example.booklog.MutableClock;
import com.example.booklog.TestcontainersConfiguration;
import com.example.booklog.common.ErrorCode;
import com.jayway.jsonpath.JsonPath;

// 書籍検索・本の取り込み（FN-02）と本の情報（FN-10）を、API を通して（Controller から DB まで）確かめる。
// 10 章の「テストの観点」のうち、サーバー側で確かめられるもの。Google Books API は呼ばず、
// 呼ぶ部品（GoogleBooksClient）を偽物にする（FN-02 5 章の 9）。使い回しと回数の数え方は本物を動かす
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MutableClock.Config.class })
@AutoConfigureMockMvc
class BookApiTests {

	private static final BookData NEKO = new BookData("zyTCAlFPjgYC", "9784101010014", "吾輩は猫である", List.of("夏目漱石"),
			"新潮社", "2003-06", "猫の目から見た人間の話。", "https://books.google.com/a");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	MutableClock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BookSearchCleanup cleanup;

	@MockitoBean
	GoogleBooksClient client;

	@BeforeEach
	void setUp() {
		this.jdbc.execute("TRUNCATE books, book_search_results, google_books_usage RESTART IDENTITY CASCADE");
		this.clock.reset();
		reset(this.client);
	}

	// TC-02-4
	@Test
	void 同じ文字で同じページを二十四時間以内に探すとGoogleBooksAPIを呼ばない() {
		when(this.client.search(any(), anyInt())).thenReturn(List.of(NEKO));

		assertThat(search("夏目漱石", 1)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"items":[{"googleBooksId":"zyTCAlFPjgYC","title":"吾輩は猫である"}],"hasNext":false}
				""");
		// 英字の大文字・小文字と空白の数が違うだけなら、同じ結果を使う
		assertThat(search(" 夏目漱石 ", 1)).hasStatusOk();
		this.clock.advance(Duration.ofHours(24).minusSeconds(1));
		assertThat(search("夏目漱石", 1)).hasStatusOk();
		verify(this.client, times(1)).search(any(), anyInt());

		// 別のページは別に探す。24 時間を過ぎたら探し直す
		assertThat(search("夏目漱石", 2)).hasStatusOk();
		this.clock.advance(Duration.ofSeconds(2));
		assertThat(search("夏目漱石", 1)).hasStatusOk();
		verify(this.client, times(3)).search(any(), anyInt());
	}

	// TC-02-5
	@Test
	void 千回に達したら探せないが前の結果は使え日本時間の0時を過ぎると探せる() {
		when(this.client.search(any(), anyInt())).thenReturn(List.of(NEKO));
		assertThat(search("猫", 1)).hasStatusOk();
		this.jdbc.update("UPDATE google_books_usage SET call_count = 1000");

		assertError(search("犬", 1), ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		assertError(importBook("other"), ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		// 前の結果を使う検索は出せる
		assertThat(search("猫", 1)).hasStatusOk();
		verify(this.client, times(1)).search(any(), anyInt());

		this.clock.advance(untilNextMidnightInJapan());
		assertThat(search("犬", 1)).hasStatusOk();
		assertThat(this.jdbc.queryForObject("SELECT call_count FROM google_books_usage WHERE usage_date = ?",
				Integer.class, LocalDate.ofInstant(this.clock.instant(), GoogleBooksCatalog.ZONE)))
			.isEqualTo(1);
	}

	// TC-02-6・TC-10-5
	@Test
	void GoogleBooksAPIが使えなくても取り込んだ本は開ける() {
		when(this.client.volume("zyTCAlFPjgYC")).thenReturn(Optional.of(NEKO));
		long id = importedId(importBook("zyTCAlFPjgYC"));
		when(this.client.search(any(), anyInt())).thenThrow(new GoogleBooksUnavailableException(0, "timeout"));
		when(this.client.volume("zyTCAlFPjgYC")).thenThrow(new GoogleBooksUnavailableException(500, "error"));

		assertError(search("猫", 1), ErrorCode.BOOK_SEARCH_UNAVAILABLE);
		// エラーの結果は保存しない（BR-12）
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM book_search_results", Integer.class)).isZero();
		// 取り込んだ本は Google Books API を呼ばずに開ける・選べる
		assertThat(this.mvc.get().uri("/api/books/{id}", id).with(loggedIn(1))).hasStatusOk();
		assertThat(importBook("zyTCAlFPjgYC")).hasStatus(HttpStatus.OK);
	}

	// B-5・B-6
	@Test
	void 選んだ本を取り込み二回目はGoogleBooksAPIを呼ばずに同じ本を返す() {
		when(this.client.volume("zyTCAlFPjgYC")).thenReturn(Optional.of(NEKO));

		MvcTestResult first = importBook("zyTCAlFPjgYC");
		assertThat(first).hasStatus(HttpStatus.CREATED);
		long id = importedId(first);
		assertThat(importBook("zyTCAlFPjgYC")).hasStatus(HttpStatus.OK)
			.bodyJson()
			.isStrictlyEqualTo("{\"id\":" + id + "}");
		verify(this.client, times(1)).volume("zyTCAlFPjgYC");

		assertThat(this.mvc.get().uri("/api/books/{id}", id).with(loggedIn(1))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"id":%d,"title":"吾輩は猫である","authors":["夏目漱石"],"publisher":"新潮社","publishedDate":"2003-06",
					 "isbn13":"9784101010014","description":"猫の目から見た人間の話。","thumbnailUrl":"https://books.google.com/a",
					 "averageRating":null,"reviewCount":0,"myReviewId":null,"myStatus":null}
					""".formatted(id));
	}

	// TC-02-7
	@Test
	void 同じ本を二人が同時に選んでも本は一つ() throws Exception {
		CountDownLatch bothFetching = new CountDownLatch(2);
		when(this.client.volume("zyTCAlFPjgYC")).thenAnswer((call) -> {
			// 2 人とも「まだない」と確かめてから取り込む
			bothFetching.countDown();
			bothFetching.await(10, TimeUnit.SECONDS);
			return Optional.of(NEKO);
		});
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<MvcTestResult> a = executor.submit(() -> importBook("zyTCAlFPjgYC"));
			Future<MvcTestResult> b = executor.submit(() -> importBook("zyTCAlFPjgYC"));
			List<Integer> statuses = List.of(a.get().getResponse().getStatus(), b.get().getResponse().getStatus());
			assertThat(statuses).containsExactlyInAnyOrder(201, 200);
			assertThat(importedId(a.get())).isEqualTo(importedId(b.get()));
		}
		finally {
			executor.shutdown();
		}
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM books", Integer.class)).isEqualTo(1);
	}

	@Test
	void GoogleBooksにない本を選ぶとM15() {
		when(this.client.volume("gone")).thenReturn(Optional.empty());
		assertError(importBook("gone"), ErrorCode.GOOGLE_BOOK_NOT_FOUND);
	}

	// TC-10-6
	@Test
	void ない本の情報はM15() {
		assertError(this.mvc.get().uri("/api/books/999999").with(loggedIn(1)).exchange(), ErrorCode.BOOK_NOT_FOUND);
	}

	// TC-02-2
	@Test
	void 空白だけで探すとM12でGoogleBooksAPIを呼ばない() {
		assertError(search("　 ", 1), ErrorCode.QUERY_REQUIRED, "q");
		verify(this.client, never()).search(any(), anyInt());
	}

	@Test
	void 一日一回古い検索の結果と呼び出し回数を消す() {
		when(this.client.search(any(), anyInt())).thenReturn(List.of());
		assertThat(search("猫", 1)).hasStatusOk();
		this.jdbc.update("INSERT INTO google_books_usage (usage_date, call_count) VALUES (?, 3)",
				LocalDate.ofInstant(this.clock.instant(), GoogleBooksCatalog.ZONE).minusDays(91));
		this.clock.advance(Duration.ofHours(25));

		this.cleanup.deleteOld();

		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM book_search_results", Integer.class)).isZero();
		assertThat(this.jdbc.queryForObject("SELECT count(*) FROM google_books_usage", Integer.class)).isEqualTo(1);
	}

	private MvcTestResult search(String q, int page) {
		return this.mvc.get().uri("/api/books/search?q={q}&page={page}", q, page).with(loggedIn(1)).exchange();
	}

	private MvcTestResult importBook(String googleBooksId) {
		return this.mvc.post()
			.uri("/api/books")
			.with(loggedIn(1))
			.with(csrf())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"googleBooksId\":\"" + googleBooksId + "\"}")
			.exchange();
	}

	// 応答の {"id":56} の数
	private static long importedId(MvcTestResult result) {
		String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		return JsonPath.<Number>read(body, "$.id").longValue();
	}

	private Duration untilNextMidnightInJapan() {
		Instant now = this.clock.instant();
		ZonedDateTime midnight = now.atZone(GoogleBooksCatalog.ZONE)
			.toLocalDate()
			.plusDays(1)
			.atStartOfDay(GoogleBooksCatalog.ZONE);
		return Duration.between(now, midnight.toInstant());
	}

}
