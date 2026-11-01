package com.example.booklog.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByTokenHash(String tokenHash);

	// 取り直しで使う。同じトークンで同時に取り直されても、新しいトークンを 2 つ渡さないよう行をロックする
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
	Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

	// 盗まれた疑いがあるとき・JWT の秘密の値を変えたときに、その人のログインをすべて切る（BR-9）
	@Modifying
	@Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
	int revokeAllByUserId(@Param("userId") long userId, @Param("now") Instant now);

	@Modifying
	@Query("delete from RefreshToken t where t.expiresAt <= :now")
	int deleteExpired(@Param("now") Instant now);

}
