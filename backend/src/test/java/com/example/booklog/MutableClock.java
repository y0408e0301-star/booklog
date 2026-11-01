package com.example.booklog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// テストで時刻を進められる Clock。ログインのロック（15 分）や JWT（15 分）・リフレッシュトークン（14 日）の期限を、
// 待たずに確かめるために使う。@Import(MutableClock.Config.class) で、アプリの Clock の代わりに使われる
public class MutableClock extends Clock {

	// PostgreSQL の timestamptz はマイクロ秒までしか持たないため、保存して読み直しても同じ時刻になるようそろえる
	private Instant now = now();

	public void reset() {
		this.now = now();
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}

	public void advance(Duration duration) {
		this.now = this.now.plus(duration);
	}

	@Override
	public Instant instant() {
		return this.now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}

	}

}
