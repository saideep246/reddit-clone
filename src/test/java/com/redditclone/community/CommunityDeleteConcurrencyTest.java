package com.redditclone.community;

import com.redditclone.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real concurrency: several threads race to delete the same community, each in its own committed transaction, against the
// local PostgreSQL. This class cannot use rolled-back test transactions, so it creates real rows and removes them in
// @AfterEach (audit rows first, then memberships, moderators, communities, users, because of the foreign keys).
@SpringBootTest
class CommunityDeleteConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private CommunityService service;
    @Autowired
    private CommunityRepository communities;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private PlatformTransactionManager txManager;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    private CommunityDeleteFixtures fx() {
        return new CommunityDeleteFixtures(jdbc, service, em);
    }

    private UUID newUser(String prefix) {
        UUID id = fx().user(prefix);
        userIds.add(id);
        return id;
    }

    private Community newCommunity(UUID creator) {
        Community c = fx().community(creator);
        communityIds.add(c.getId());
        return c;
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : communityIds) {
            jdbc.update("DELETE FROM moderation_actions WHERE community_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE community_id = ?", id);
            jdbc.update("DELETE FROM community_moderators WHERE community_id = ?", id);
            jdbc.update("DELETE FROM communities WHERE id = ?", id);
        }
        for (UUID id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    // Runs every task at the same instant (all threads wait at a barrier first) and returns each one's outcome.
    private <T> List<Object> raceAll(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier startTogether = new CyclicBarrier(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    startTogether.await(10, TimeUnit.SECONDS);
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            stop(pool);
        }
    }

    // Waits for every worker thread to finish before the test (and then @AfterEach cleanup) moves on. Without the wait, a test
    // that fails early could have its cleanup run while a delete is still mid-transaction, which then writes its rows after
    // the cleanup and leaves debris behind.
    private static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        pool.awaitTermination(30, TimeUnit.SECONDS);
    }

    @Test
    void ofManyConcurrentCompareAndSetUpdatesExactlyOneWins() throws Exception {
        UUID creator = newUser("ccowner");
        Community c = newCommunity(creator);
        List<UUID> actors = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            actors.add(newUser("ccactor"));
        }
        TransactionTemplate tx = new TransactionTemplate(txManager);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (UUID actor : actors) {
            tasks.add(() -> tx.execute(status -> communities.markDeleted(c.getId(), actor)));
        }

        List<Object> outcomes = raceAll(tasks);

        long winners = outcomes.stream().filter(o -> Integer.valueOf(1).equals(o)).count();
        long losers = outcomes.stream().filter(o -> Integer.valueOf(0).equals(o)).count();
        assertEquals(1, winners, "exactly one update may affect the row: " + outcomes);
        assertEquals(THREADS - 1, losers, "every other update must affect 0 rows: " + outcomes);
        UUID winner = actors.get(outcomes.indexOf(1));
        assertEquals(winner, fx().deletedBy(c.getId()), "deleted_by records the one winner, not a loser");
        assertTrue(fx().isDeleted(c.getId()));
    }

    @Test
    void ofManyConcurrentServiceDeletesExactlyOneSucceedsAndTheRestAreNotFound() throws Exception {
        // repeated with fresh communities so the threads genuinely overlap past the "already deleted?" pre-check at least some
        // of the time; the guarantee has to hold either way
        for (int round = 0; round < 5; round++) {
            UUID creator = newUser("ccowner");
            Community c = newCommunity(creator);
            List<Callable<Boolean>> tasks = Collections.nCopies(THREADS, () -> {
                service.deleteCommunity(creator, c.getId(), c.getName());
                return true;
            });

            List<Object> outcomes = raceAll(tasks);

            long successes = outcomes.stream().filter(Boolean.TRUE::equals).count();
            long notFound = outcomes.stream().filter(o -> o instanceof NotFoundException).count();
            assertEquals(1, successes, "round " + round + ": exactly one delete may succeed: " + outcomes);
            assertEquals(THREADS - 1, notFound, "round " + round + ": every other attempt must be a plain 404: " + outcomes);
            assertEquals(1, fx().auditRows(c.getId()), "round " + round + ": one audit row, written by the winner only");
            assertEquals(creator, fx().deletedBy(c.getId()));
        }
    }

    // The branch the tests above only hit by luck, made deterministic with a real second transaction. A rival's delete has
    // already UPDATEd the row but not committed (so it holds the row lock and the committed row still looks un-deleted). The
    // service's "already deleted?" pre-check therefore passes, and its compare-and-set blocks on the lock. When the rival
    // commits, the compare-and-set re-checks the row, matches nothing, and the caller must get a 404 - with no audit row, and
    // without overwriting the rival's record.
    @Test
    void aDeleteThatLosesTheRaceAfterItsPreCheckGetsA404WritesNoAuditAndKeepsTheRivalsRecord() throws Exception {
        UUID creator = newUser("ccowner");
        UUID rival = newUser("ccrival");
        Community c = newCommunity(creator);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch rivalHoldsLock = new CountDownLatch(1);
        CountDownLatch commitRival = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> rivalTransaction = pool.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", rival, c.getId());
                rivalHoldsLock.countDown();
                try {
                    commitRival.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(rivalHoldsLock.await(10, TimeUnit.SECONDS), "the rival's transaction never took the row lock");

            Future<Object> loser = pool.submit(() -> {
                try {
                    service.deleteCommunity(creator, c.getId(), c.getName());
                    return "deleted";
                } catch (Exception e) {
                    return e;
                }
            });
            awaitAnUpdateBlockedOnTheRowLock();

            commitRival.countDown();
            rivalTransaction.get(10, TimeUnit.SECONDS);
            Object outcome = loser.get(10, TimeUnit.SECONDS);

            assertTrue(outcome instanceof NotFoundException, "the loser must get a plain 404, got: " + outcome);
            assertEquals(rival, fx().deletedBy(c.getId()), "the rival's record must not be overwritten");
            assertEquals(0, fx().auditRows(c.getId()), "the loser must not write an audit row");
        } finally {
            commitRival.countDown();
            stop(pool);
        }
    }

    // Proves the loser has really reached its compare-and-set and is waiting on the rival's lock (not merely that time passed).
    private void awaitAnUpdateBlockedOnTheRowLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer blocked = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event_type = 'Lock' AND query ILIKE 'UPDATE communities SET deleted_at%AND deleted_at IS NULL%'""",
                    Integer.class);
            if (blocked != null && blocked > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the delete never blocked on the row lock, so the race was not set up");
    }
}
