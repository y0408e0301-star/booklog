package com.example.booklog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.booklog.user.User;

// 単体テスト（DB を使わないもの）で使う準備。
// エンティティの ID はデータベースが決めるので、保存せずに使うときはここで入れる
public final class TestEntities {

	// 単体テストの「今」。Clock.fixed で止めておく
	public static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");

	private TestEntities() {
	}

	public static Clock fixedClock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}

	public static User user(long id, String username) {
		return withId(new User(username, username + "の表示名", "hash-" + username, NOW), id);
	}

	public static <T> T withId(T entity, long id) {
		ReflectionTestUtils.setField(entity, "id", id);
		return entity;
	}

	// トランザクションを開かず、渡された処理をそのまま実行する（Service の単体テスト用）
	public static TransactionTemplate immediateTransaction() {
		return new TransactionTemplate() {

			@Override
			public <T> T execute(TransactionCallback<T> action) {
				return action.doInTransaction(new SimpleTransactionStatus());
			}

		};
	}

}
