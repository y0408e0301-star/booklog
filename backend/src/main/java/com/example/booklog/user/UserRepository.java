package com.example.booklog.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

// ユーザー ID は大文字・小文字を区別せずに探す。lower(username) の一意索引を使うよう、lower() で比べる
public interface UserRepository extends JpaRepository<User, Long> {

	@Query("select u from User u where lower(u.username) = lower(:username)")
	Optional<User> findByUsername(@Param("username") String username);

	// ログインで使う。同じユーザー ID へのログインが同時に来ても、失敗の回数を取りこぼさないよう行をロックする
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where lower(u.username) = lower(:username)")
	Optional<User> findByUsernameForUpdate(@Param("username") String username);

	@Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
	boolean existsByUsername(@Param("username") String username);

}
