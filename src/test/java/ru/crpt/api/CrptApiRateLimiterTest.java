package ru.crpt.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiRateLimiterTest {

    @Test
    void tryAcquire_withinLimit_thenExceed() {
        // Окно = 1 секунда, лимит = 2. Три быстрых вызова должны дать: true, true, false
        CrptApi api = new CrptApi(TimeUnit.SECONDS, 2);

        assertTrue(api.tryAcquirePermit(), "Первое разрешение должно быть выдано");
        assertTrue(api.tryAcquirePermit(), "Второе разрешение должно быть выдано");
        assertFalse(api.tryAcquirePermit(), "Третье разрешение должно быть отклонено в пределах того же окна 1с");
    }

    @Test
    void tryAcquire_recoversAfterWindowElapsed() throws InterruptedException {
        // Окно = 1 миллисекунда, лимит = 1.
        CrptApi api = new CrptApi(TimeUnit.MILLISECONDS, 1);

        assertTrue(api.tryAcquirePermit(), "Первое разрешение должно быть выдано");

        // Ждем немного больше длительности окна, чтобы лимит гарантированно восстановился
        Thread.sleep(5);

        assertTrue(api.tryAcquirePermit(), "Разрешение должно быть выдано после истечения окна");
    }

    @Test
    void acquire_blocksUntilNextWindow() throws InterruptedException {
        CrptApi api = new CrptApi(TimeUnit.MILLISECONDS, 1);

        api.acquirePermit(); // потребляем единственное разрешение в текущем окне

        long start = System.nanoTime();
        api.acquirePermit(); // должен блокироваться примерно до начала следующего окна 1 мс
        long elapsedMicros = (System.nanoTime() - start) / 1_000L;

        // Ожидаем, что второй acquire занял хотя бы ~500 микросекунд (щадящая нижняя граница)
        assertTrue(elapsedMicros >= 500, "Второй acquire должен блокироваться до следующего окна; затрачено=" + elapsedMicros + " мкс");
    }
}
