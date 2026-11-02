package com.example.booklog.review;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.booklog.common.ErrorCode;

// レビューの星・感想・ネタバレありの確かめ方（FN-03 4 章）
class ReviewInputTests {

	// TC-03-1
	@Test
	void 星を選んでいなければM16() {
		assertApiError(() -> ReviewInput.of(null, "感想", false), ErrorCode.RATING_REQUIRED, "rating");
	}

	// TC-03-1
	@ParameterizedTest
	@ValueSource(ints = { 0, 6, -1 })
	void 星は1から5まで(int rating) {
		assertApiError(() -> ReviewInput.of(rating, "感想", false), ErrorCode.BAD_REQUEST, "rating");
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 5 })
	void 星の境界(int rating) {
		assertThat(ReviewInput.of(rating, "感想", false).rating()).isEqualTo(rating);
	}

	// TC-03-2
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "\n\r\n", "　\t" })
	void 感想がトリムして空ならM17(String body) {
		assertApiError(() -> ReviewInput.of(3, body, false), ErrorCode.BODY_REQUIRED, "body");
	}

	// TC-03-2
	@Test
	void 感想は2000文字までで絵文字も1文字と数える() {
		assertThat(ReviewInput.of(3, "あ", false).body()).isEqualTo("あ");
		assertThat(ReviewInput.of(3, "😀".repeat(2000), false).body()).hasSize(4000);
		assertApiError(() -> ReviewInput.of(3, "あ".repeat(2001), false), ErrorCode.BAD_REQUEST, "body");
	}

	// TC-03-2：CRLF を LF にそろえてから数える
	@Test
	void 改行をLFにそろえてから数える() {
		String crlf = "あ\r\n".repeat(1000);
		ReviewInput input = ReviewInput.of(3, crlf, false);
		// 最後の改行はトリムで消える
		assertThat(input.body()).isEqualTo("あ\n".repeat(999) + "あ");
		assertThat(ReviewInput.of(3, "一\r二\r\n三", false).body()).isEqualTo("一\n二\n三");
	}

	@Test
	void ネタバレありは省いたらfalse() {
		assertThat(ReviewInput.of(3, "感想", null).spoiler()).isFalse();
		assertThat(ReviewInput.of(3, "感想", true).spoiler()).isTrue();
	}

}
