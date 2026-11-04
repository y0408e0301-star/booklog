package com.example.booklog.comment;

import static com.example.booklog.ApiAssertions.assertApiError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import com.example.booklog.TestEntities;
import com.example.booklog.common.ErrorCode;
import com.example.booklog.user.User;
import com.example.booklog.user.UserRepository;

// コメントの処理の順番とエラー（FN-06）
@ExtendWith(MockitoExtension.class)
class CommentServiceTests {

	@Mock
	CommentRepository comments;

	@Mock
	UserRepository users;

	private CommentService service;

	private final User taro = TestEntities.user(1, "taro_y");

	private final User hanako = TestEntities.user(2, "hanako");

	@BeforeEach
	void setUp() {
		this.service = new CommentService(this.comments, this.users, TestEntities.fixedClock());
	}

	// TC-06-1・C-2：改行を LF にそろえてトリムする
	@Test
	void 改行をそろえてトリムして付ける() {
		when(this.comments.reviewExists(7)).thenReturn(true);
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.comments.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 901));

		CommentView view = this.service.create(1, 7, "  一行目\r\n二行目\r  ");
		assertThat(view.id()).isEqualTo(901);
		assertThat(view.body()).isEqualTo("一行目\n二行目");
		assertThat(view.mine()).isTrue();
		assertThat(view.author().username()).isEqualTo("taro_y");
		ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
		verify(this.comments).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getReviewId()).isEqualTo(7);
	}

	// TC-06-1：0 文字（空白・改行だけ）・500 文字・501 文字
	@Test
	void 本文は1から500文字() {
		assertApiError(() -> this.service.create(1, 7, " \n "), ErrorCode.COMMENT_REQUIRED, "body");
		assertApiError(() -> this.service.create(1, 7, null), ErrorCode.COMMENT_REQUIRED, "body");
		// 絵文字も 1 文字と数える
		assertApiError(() -> this.service.create(1, 7, "😀".repeat(501)), ErrorCode.BAD_REQUEST, "body");
		verifyNoInteractions(this.comments);

		when(this.comments.reviewExists(7)).thenReturn(true);
		when(this.users.findById(1L)).thenReturn(Optional.of(this.taro));
		when(this.comments.saveAndFlush(any())).thenAnswer((call) -> TestEntities.withId(call.getArgument(0), 901));
		assertThat(this.service.create(1, 7, "😀".repeat(500)).body()).hasSize(1000);
	}

	@Test
	void 削除されたレビューには付けられず一覧も読めない() {
		when(this.comments.reviewExists(999)).thenReturn(false);
		assertApiError(() -> this.service.create(1, 999, "本文"), ErrorCode.REVIEW_NOT_FOUND);
		assertApiError(() -> this.service.list(1, 999, null), ErrorCode.REVIEW_NOT_FOUND);
	}

	// TC-06-2：51 件あれば 50 件と続きの位置
	@Test
	void 五十件ずつ読み続きの位置を返す() {
		when(this.comments.reviewExists(7)).thenReturn(true);
		List<Comment> found = LongStream.rangeClosed(1, 51).mapToObj(this::comment).toList();
		when(this.comments.findAfter(7, 0, Limit.of(51))).thenReturn(found);

		CommentService.CommentPage page = this.service.list(2, 7, null);
		assertThat(page.items()).hasSize(50);
		assertThat(page.items().getFirst().mine()).isTrue();
		assertThat(page.nextCursor()).isEqualTo("50");
	}

	@Test
	void 最後まで読めば続きの位置はnull() {
		when(this.comments.reviewExists(7)).thenReturn(true);
		when(this.comments.findAfter(7, 50, Limit.of(51))).thenReturn(List.of(comment(51)));
		CommentService.CommentPage page = this.service.list(1, 7, "50");
		assertThat(page.items()).hasSize(1);
		assertThat(page.items().getFirst().mine()).isFalse();
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	void 続きの位置が数でなければ400() {
		assertApiError(() -> this.service.list(1, 7, "x"), ErrorCode.BAD_REQUEST, "cursor");
	}

	// C-5・5 章の 3
	@Test
	void 消せるのは書いた人だけ() {
		Comment mine = comment(901);
		when(this.comments.findWithAuthorById(901L)).thenReturn(Optional.of(mine));
		assertApiError(() -> this.service.delete(1, 901), ErrorCode.FORBIDDEN);
		verify(this.comments, never()).delete(any());

		this.service.delete(2, 901);
		verify(this.comments).delete(mine);
	}

	@Test
	void ないコメントは404() {
		when(this.comments.findWithAuthorById(999L)).thenReturn(Optional.empty());
		assertApiError(() -> this.service.delete(2, 999), ErrorCode.COMMENT_NOT_FOUND);
	}

	// はなこが書いたコメント
	private Comment comment(long id) {
		return TestEntities.withId(new Comment(7, this.hanako, "コメント " + id, TestEntities.NOW), id);
	}

}
