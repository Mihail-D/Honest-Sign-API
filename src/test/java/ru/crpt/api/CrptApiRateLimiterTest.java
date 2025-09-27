package ru.crpt.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiRateLimiterTest {

    @Test
    void tryAcquire_withinLimit_thenExceed() {
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 2);

        assertTrue(api.tryAcquirePermit());
        assertTrue(api.tryAcquirePermit());
        assertFalse(api.tryAcquirePermit());
    }

    @Test
    void tryAcquire_recoversAfterWindowElapsed() throws InterruptedException {
        CrptApi api = new CrptApi(TimeUnit.MILLISECONDS, 1);

        assertTrue(api.tryAcquirePermit());

        Thread.sleep(5);

        assertTrue(api.tryAcquirePermit());
    }

    @Test
    void acquire_blocksUntilNextWindow() throws InterruptedException {
        CrptApi api = new CrptApi(TimeUnit.MILLISECONDS, 1);

        api.acquirePermit();

        long start = System.nanoTime();
        api.acquirePermit();
        long elapsedMicros = (System.nanoTime() - start) / 1_000L;

        assertTrue(elapsedMicros >= 500);
    }
}
