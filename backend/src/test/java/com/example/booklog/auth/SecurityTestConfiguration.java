package com.example.booklog.auth;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.example.booklog.user.UserRepository;

// Controller のテスト（@WebMvcTest）でも、本物のログインの確かめ方（SecurityConfig・JwtConfig）を使う。
// 2 つはこのパッケージの中だけで見えるクラスなので、ここから読み込む
@TestConfiguration(proxyBeanMethods = false)
@Import({ SecurityConfig.class, JwtConfig.class, AuthCookies.class })
public class SecurityTestConfiguration {

	// SecurityConfig がユーザーがいるかを確かめるのに使う。データベースは使わないので、いつもいることにする
	@Bean
	UserRepository userRepository() {
		UserRepository users = Mockito.mock(UserRepository.class);
		Mockito.when(users.existsById(Mockito.anyLong())).thenReturn(true);
		return users;
	}

}
