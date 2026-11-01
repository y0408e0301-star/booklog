package com.example.booklog.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 今の時刻はこの Clock から読む（テストで時刻を進めて、ログインのロックや JWT の期限を確かめるため）
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
