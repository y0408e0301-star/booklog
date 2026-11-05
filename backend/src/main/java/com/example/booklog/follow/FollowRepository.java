package com.example.booklog.follow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.booklog.user.User;

public interface FollowRepository extends JpaRepository<Follow, Follow.Key> {

	// すでにあるときは何もしない。同時に押されても行は 1 つ（F-4、FN-07 5 章の 1）
	@Modifying
	@Query(value = """
			INSERT INTO follows (follower_id, followee_id, created_at) VALUES (:followerId, :followeeId, now())
			ON CONFLICT DO NOTHING
			""", nativeQuery = true)
	void insertIfAbsent(@Param("followerId") long followerId, @Param("followeeId") long followeeId);

	// ないときは何もしない
	@Modifying
	@Query("delete from Follow f where f.followerId = :followerId and f.followeeId = :followeeId")
	void deleteByFollowerIdAndFolloweeId(@Param("followerId") long followerId, @Param("followeeId") long followeeId);

	long countByFolloweeId(long followeeId);

	long countByFollowerId(long followerId);

	boolean existsByFollowerIdAndFolloweeId(long followerId, long followeeId);

	// userId がフォローしている人を、cursor（日時と ID）より後ろから新しくフォローした順に。
	// 同じ日時ならユーザーの ID の大きい順（F-5、FN-07 5 章の 3）
	@Query("""
			select new com.example.booklog.follow.FollowRepository$Row(u, f.createdAt)
			from Follow f join User u on u.id = f.followeeId
			where f.followerId = :userId
			  and (f.createdAt < :at or (f.createdAt = :at and u.id < :id))
			order by f.createdAt desc, u.id desc
			""")
	List<Row> findFollowing(@Param("userId") long userId, @Param("at") Instant at, @Param("id") long id, Limit limit);

	// userId をフォローしている人。並べ方は findFollowing と同じ
	@Query("""
			select new com.example.booklog.follow.FollowRepository$Row(u, f.createdAt)
			from Follow f join User u on u.id = f.followerId
			where f.followeeId = :userId
			  and (f.createdAt < :at or (f.createdAt = :at and u.id < :id))
			order by f.createdAt desc, u.id desc
			""")
	List<Row> findFollowers(@Param("userId") long userId, @Param("at") Instant at, @Param("id") long id, Limit limit);

	// userIds のうち、followerId がフォローしている人（UserSummary の followedByMe）
	@Query("select f.followeeId from Follow f where f.followerId = :followerId and f.followeeId in :userIds")
	List<Long> findFolloweeIds(@Param("followerId") long followerId, @Param("userIds") Collection<Long> userIds);

	// 一覧の 1 行：ユーザーとフォローした日時（続きの位置に使う）
	record Row(User user, Instant followedAt) {
	}

}
