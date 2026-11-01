package com.example.booklog.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

// Controller のテスト（@WebMvcTest）でも、本物のログインの確かめ方（SecurityConfig・JwtConfig）を使う。
// 2 つはこのパッケージの中だけで見えるクラスなので、ここから読み込む
@TestConfiguration(proxyBeanMethods = false)
@Import({ SecurityConfig.class, JwtConfig.class, AuthCookies.class })
public class SecurityTestConfiguration {

}
