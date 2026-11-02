package com.example.booklog.book;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

// 書籍検索・本の取り込み（機能定義書 FN-02 6 章）と、本の情報（FN-10 6 章）の API
@RestController
@RequestMapping("/api/books")
class BookController {

	private final BookService bookService;

	BookController(BookService bookService) {
		this.bookService = bookService;
	}

	@Tag(name = "FN-02 書籍検索・登録")
	@Operation(summary = "本を探す",
			description = "ISBN か書名・著者名で Google Books API を探す。同じ文字・同じページは 24 時間使い回す。"
					+ "Google Books API が使えないとき・その日の上限に達したときは 503")
	@GetMapping("/search")
	BookSearchPage search(
			@Parameter(description = "ISBN か書名・著者名。前後の空白を除いて 1〜100 文字（B-1・B-2）") @RequestParam(
					required = false) @Nullable String q,
			@Parameter(description = "1〜5。省いたら 1（B-3）") @RequestParam(required = false) @Nullable Integer page) {
		return this.bookService.search(q, page);
	}

	@Tag(name = "FN-02 書籍検索・登録")
	@Operation(summary = "本を取り込む", description = "検索の結果から選んだ本を取り込み、本の ID を返す。もう取り込んであれば Google Books API を呼ばない")
	@ApiResponse(responseCode = "201", description = "新しく取り込んだ")
	@ApiResponse(responseCode = "200", description = "もう取り込んであった")
	@PostMapping
	ResponseEntity<ImportedBook> importBook(@RequestBody ImportBookRequest request) {
		BookService.ImportResult result = this.bookService.importBook(request.googleBooksId());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(new ImportedBook(result.id()));
	}

	@Tag(name = "FN-10 本のページ")
	@Operation(summary = "本の情報", description = "取り込んだ本の情報と、レビューの平均評価・件数・自分のレビュー。Google Books API は呼ばない")
	@GetMapping("/{id}")
	BookService.BookDetail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
		return this.bookService.detail(Long.parseLong(jwt.getSubject()), id);
	}

	record ImportBookRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED,
			description = "検索の結果の googleBooksId。1〜40 文字の英数字と「-」「_」") @Nullable String googleBooksId) {
	}

	record ImportedBook(long id) {
	}

}
