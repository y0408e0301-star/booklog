package com.example.booklog.helpful;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.booklog.common.ErrorCode;

// 参考になったの処理の順番とエラー（FN-05）
@ExtendWith(MockitoExtension.class)
class HelpfulServiceTests {

	@Mock
	Helpfuls helpfuls;

	private HelpfulService service;

	@BeforeEach
	void setUp() {
		this.service = new HelpfulService(this.helpfuls);
	}

	@Test
	void 付けると最新の件数を返す() {
		author(7, 2);
		when(this.helpfuls.count(7)).thenReturn(13L);
		assertThat(this.service.mark(1, 7)).isEqualTo(new HelpfulService.HelpfulResult(true, 13));
		verify(this.helpfuls).add(1, 7);
	}

	@Test
	void 取り消すと最新の件数を返す() {
		author(7, 2);
		when(this.helpfuls.count(7)).thenReturn(12L);
		assertThat(this.service.unmark(1, 7)).isEqualTo(new HelpfulService.HelpfulResult(false, 12));
		verify(this.helpfuls).remove(1, 7);
	}

	// H-4・TC-05-3
	@Test
	void 自分のレビューには付けられない() {
		author(7, 1);
		assertApiError(() -> this.service.mark(1, 7), ErrorCode.CANNOT_MARK_OWN_REVIEW);
		verify(this.helpfuls, never()).add(1, 7);
	}

	@Test
	void ないレビューは付けても取り消しても404() {
		when(this.helpfuls.authorOf(999)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.mark(1, 999), ErrorCode.REVIEW_NOT_FOUND);
		assertApiError(() -> this.service.unmark(1, 999), ErrorCode.REVIEW_NOT_FOUND);
		verify(this.helpfuls, never()).add(1, 999);
		verify(this.helpfuls, never()).remove(1, 999);
	}

	@Test
	void 確かめた後でレビューが消えたら404() {
		author(7, 2);
		doThrow(new DataIntegrityViolationException("fk")).when(this.helpfuls).add(1, 7);
		assertApiError(() -> this.service.mark(1, 7), ErrorCode.REVIEW_NOT_FOUND);
	}

	private void author(long reviewId, long userId) {
		when(this.helpfuls.authorOf(reviewId)).thenReturn(Optional.of(userId));
	}

}
