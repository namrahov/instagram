package az.instagram;

import static org.assertj.core.api.Assertions.assertThat;

import az.instagram.entity.MediaEntity;
import az.instagram.entity.MediaFormat;
import az.instagram.entity.MediaStatus;
import az.instagram.repository.LikeRepository;
import az.instagram.repository.MediaRepository;
import az.instagram.service.PostService;
import az.instagram.worker.LikeCounterJob;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Section 10/11 against the real PostgreSQL. The scheduled counter runs once at startup and then waits an hour;
 * the tests call it directly.
 */
@SpringBootTest(properties = {
        "instagram.video-worker.enabled=false",
        "instagram.outbox.publisher.enabled=false",
        "instagram.likes.counter.interval=PT1H",
})
class LikeCounterIT {

    private static final long AUTHOR = 999_997;
    /** Likers: ids far away from real users. */
    private static final long FIRST_LIKER = 50_000_000;

    @Autowired PostService posts;
    @Autowired LikeRepository likes;
    @Autowired LikeCounterJob counter;
    @Autowired MediaRepository media;
    @Autowired JdbcClient jdbc;
    @Autowired TransactionTemplate tx;

    private final List<Long> createdPosts = new ArrayList<>();
    private final List<Long> createdMedia = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long id : createdPosts) {
            jdbc.sql("DELETE FROM like_events WHERE post_id = :id").param("id", id).update();
            jdbc.sql("DELETE FROM posts WHERE id = :id").param("id", id).update(); // cascades likes, count
        }
        for (long id : createdMedia) {
            jdbc.sql("DELETE FROM media WHERE id = :id").param("id", id).update();
        }
    }

    @Test
    void tenThousandParallelLikesAreCountedExactlyInBatches() throws Exception {
        long post = newPost();
        int users = 10_000;

        long started = System.nanoTime();
        runInParallel(users, i -> posts.like(FIRST_LIKER + i, post));
        long likeMs = (System.nanoTime() - started) / 1_000_000;

        // Nothing counted yet: the request path never touches the counter row.
        assertThat(likes.count(post)).isZero();
        assertThat(queued(post)).isEqualTo(users);

        started = System.nanoTime();
        assertThat(counter.applyQueuedLikes()).isEqualTo(users);
        long countMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(likes.count(post)).isEqualTo(users);
        assertThat(queued(post)).isZero();
        System.out.printf("%d likes from 32 threads in %d ms; counted in %d ms (10 batches of 1000, one UPDATE each)%n",
                users, likeMs, countMs);
    }

    @Test
    void duplicatesUnlikesAndTwoCounterInstancesRacingStillGiveTheExactCount() throws Exception {
        long post = newPost();
        int users = 2_000;

        // Two "app instances" keep draining the queue while likes pour in.
        AtomicBoolean done = new AtomicBoolean();
        ExecutorService counters = Executors.newFixedThreadPool(2);
        List<Future<?>> running = new ArrayList<>();
        for (int c = 0; c < 2; c++) {
            running.add(counters.submit(() -> {
                while (!done.get()) {
                    counter.applyQueuedLikes();
                }
            }));
        }

        // Every user likes twice (double tap / retry); every fifth then unlikes, twice.
        runInParallel(users * 2, i -> posts.like(FIRST_LIKER + i % users, post));
        runInParallel(users / 5 * 2, i -> posts.unlike(FIRST_LIKER + (i % (users / 5)) * 5, post));

        done.set(true);
        for (Future<?> f : running) {
            f.get();
        }
        counters.shutdown();
        counter.applyQueuedLikes();

        long rows = jdbc.sql("SELECT count(*) FROM likes WHERE post_id = :p").param("p", post).query(Long.class).single();
        assertThat(rows).isEqualTo(users - users / 5);
        assertThat(likes.count(post)).as("count matches the like rows").isEqualTo(rows);
        assertThat(queued(post)).isZero();
    }

    @Test
    void likeAndUnlikeAreIdempotentAndLikedByMeIsExactAtOnce() {
        long post = newPost();
        long me = FIRST_LIKER;

        posts.like(me, post);
        posts.like(me, post);
        assertThat(queued(post)).as("second like queued nothing").isEqualTo(1);
        // Before the counter runs: my like is visible to me, the count is not there yet.
        PostService.PostView view = posts.get(me, post);
        assertThat(view.likedByMe()).isTrue();
        assertThat(view.likeCount()).isZero();

        counter.applyQueuedLikes();
        assertThat(posts.get(me, post).likeCount()).isEqualTo(1);

        posts.unlike(me, post);
        posts.unlike(me, post);
        posts.like(me, post);
        posts.unlike(me, post);
        counter.applyQueuedLikes();
        assertThat(posts.get(me, post).likeCount()).isZero();
        assertThat(posts.get(me, post).likedByMe()).isFalse();
    }

    @Test
    void aBatchThatFailsHalfwayIsNeitherCountedNorLost() {
        long post = newPost();
        runInParallel(50, i -> posts.like(FIRST_LIKER + i, post));

        // The batch runs, then its transaction is rolled back (as if the job crashed before commit).
        tx.executeWithoutResult(status -> {
            assertThat(likes.applyQueuedChanges(1000)).isGreaterThanOrEqualTo(50);
            status.setRollbackOnly();
        });
        assertThat(likes.count(post)).isZero();
        assertThat(queued(post)).as("still queued").isEqualTo(50);

        counter.applyQueuedLikes();
        assertThat(likes.count(post)).isEqualTo(50);
    }

    private long newPost() {
        Instant now = Instant.now();
        String key = "media/" + AUTHOR + "/like-it-" + UUID.randomUUID() + ".jpg";
        MediaEntity image = media.save(new MediaEntity(AUTHOR, MediaFormat.JPEG, 1, key, now, now.plusSeconds(60)));
        media.markVerified(image.getId(), 1, null, now, MediaStatus.PENDING_UPLOAD, MediaStatus.READY);
        createdMedia.add(image.getId());
        long post = posts.create(AUTHOR, image.getId(), "like test").getId();
        createdPosts.add(post);
        return post;
    }

    private long queued(long post) {
        return jdbc.sql("SELECT count(*) FROM like_events WHERE post_id = :p").param("p", post)
                .query(Long.class).single();
    }

    private static void runInParallel(int count, java.util.function.IntConsumer task) {
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            List<Future<?>> futures = IntStream.range(0, count)
                    .mapToObj(i -> pool.submit(() -> task.accept(i)))
                    .<Future<?>>map(f -> f)
                    .toList();
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
