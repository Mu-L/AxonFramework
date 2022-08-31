package org.axonframework.lifecycle;

import org.junit.jupiter.api.*;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests targeted towards the {@link ShutdownLatch}.
 *
 * @author Steven van Beelen
 */
class ShutdownLatchTest {

    private final ShutdownLatch testSubject = new ShutdownLatch();

    @Test
    void initializeCancelsEarlierShutdown() {
        testSubject.registerActivity();

        CompletableFuture<Void> latch = testSubject.initiateShutdown();
        testSubject.initialize();

        assertTrue(latch.isCompletedExceptionally());
    }

    @Test
    void incrementThrowsShutdownInProgressExceptionIfShuttingDown() {
        testSubject.initiateShutdown();

        assertThrows(ShutdownInProgressException.class, testSubject::registerActivity);
    }

    @Test
    void decrementCompletesWaitProcess() {
        ShutdownLatch.ActivityHandle activityHandle = testSubject.registerActivity();

        CompletableFuture<Void> latch = testSubject.initiateShutdown();

        assertFalse(latch.isDone());

        activityHandle.end();

        assertTrue(latch.isDone());
    }

    @Test
    void isShuttingDownIsFalseForNonAwaitedLatch() {
        assertFalse(testSubject.isShuttingDown());
    }

    @Test
    void initiateShutdownOnEmptyLatchOpensImmediately() {
        CompletableFuture<Void> latch = testSubject.initiateShutdown();

        assertTrue(latch.isDone());
    }

    @Test
    void isShuttingDownIsTrueForAwaitedLatch() {
        CompletableFuture<Void> latch = testSubject.initiateShutdown();

        assertTrue(testSubject.isShuttingDown());
        assertTrue(latch.isDone());
    }

    @Test
    void isShuttingDownThrowsSuppliedExceptionForAwaitedLatch() {
        CompletableFuture<Void> latch = testSubject.initiateShutdown();

        assertThrows(SomeException.class, () -> testSubject.ifShuttingDown(SomeException::new));
        assertTrue(latch.isDone());
    }

    @Test
    void subsequentActivityHandleEndCallsDoNotInfluenceOtherHandles() {
        ShutdownLatch.ActivityHandle handleOne = testSubject.registerActivity();
        ShutdownLatch.ActivityHandle handleTwo = testSubject.registerActivity();

        // Calling end twice on the first handle should not make the latch closed
        handleOne.end();
        handleOne.end();

        CompletableFuture<Void> latch = testSubject.initiateShutdown();
        assertFalse(latch.isDone());
        // Only ending to other activity handle will open the latch
        handleTwo.end();
        assertTrue(latch.isDone());
    }

    private static class SomeException extends RuntimeException {

    }
}