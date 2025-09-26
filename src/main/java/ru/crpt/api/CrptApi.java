package ru.crpt.api;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Клиент API Честного знака.
 * <p>
 * На данном этапе реализовано ограничение количества
 * запросов в фиксированном временном окне (1 единица {@link TimeUnit}).
 * Будущие сетевые методы обязаны вызывать {@link #acquirePermit()} перед
 * обращением к удаленному API.
 */
public final class CrptApi {
    /** Внутренний лимитер запросов. */
    private final RateLimiter rateLimiter;

    /**
     * Создает инстанс API клиента с ограничением количества запросов в интервале.
     *
     * @param timeUnit     единица времени, определяющая длину окна (строго 1 единица: 1 секунда, 1 минута и т.п.)
     * @param requestLimit положительное число запросов, допустимых в каждом окне
     * @throws NullPointerException     если {@code timeUnit} == null
     * @throws IllegalArgumentException если {@code requestLimit} <= 0
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        Objects.requireNonNull(timeUnit, "единица времени");
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit должен быть > 0");
        }
        this.rateLimiter = new FixedWindowRateLimiter(requestLimit, timeUnit);
    }

    // ------------------- ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ БУДУЩИХ ВЫЗОВОВ -------------------

    /**
     * Блокирующее ожидание разрешения на выполнение одного запроса в рамках лимита.
     * Должно вызываться непосредственно перед сетевым обращением.
     *
     * @throws InterruptedException если поток был прерван во время ожидания
     */
    void acquirePermit() throws InterruptedException {
        rateLimiter.acquire();
    }

    /**
     * Неблокирующая попытка получить разрешение на запрос.
     *
     * @return {@code true}, если разрешение получено немедленно, иначе {@code false}
     */
    boolean tryAcquirePermit() {
        return rateLimiter.tryAcquire();
    }

    // ------------------- ВНУТРЕННИЕ ТИПЫ -------------------

    /** Простейший контракт лимитера. */
    interface RateLimiter {
        void acquire() throws InterruptedException;
        boolean tryAcquire();
    }

    /**
     * Реализация лимитера с фиксированным окном: не более N запросов за 1 интервал timeUnit.
     * Потокобезопасная, с использованием справедливой блокировки.
     */
    static final class FixedWindowRateLimiter implements RateLimiter {
        private final int limit;
        private final long windowNanos; // длительность окна

        private final ReentrantLock lock = new ReentrantLock(true); // справедливая блокировка
        private final Condition nextWindowCond = lock.newCondition();

        private long windowStartNanos; // начало текущего окна
        private int usedInWindow;      // сколько уже выдано разрешений в окне

        FixedWindowRateLimiter(int limit, TimeUnit unit) {
            if (limit <= 0) throw new IllegalArgumentException("limit должен быть > 0");
            Objects.requireNonNull(unit, "единица времени");
            this.limit = limit;
            this.windowNanos = unit.toNanos(1L); // окно = 1 единица времени
            this.windowStartNanos = System.nanoTime();
            this.usedInWindow = 0;
        }

        @Override
        public void acquire() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (true) {
                    final long now = System.nanoTime();
                    resetWindowIfElapsed(now);
                    if (usedInWindow < limit) {
                        usedInWindow++;
                        return;
                    }
                    long waitNanos = nanosUntilNextWindow(now);
                    if (waitNanos <= 0) {
                        // На всякий случай, чтобы избежать активного ожидания — короткое ожидание
                        waitNanos = Math.max(50_000L, windowNanos / 100); // >=50 мкс или 1% окна
                    }
                    nextWindowCond.awaitNanos(waitNanos);
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean tryAcquire() {
            if (!lock.tryLock()) {
                return false;
            }
            try {
                final long now = System.nanoTime();
                resetWindowIfElapsed(now);
                if (usedInWindow < limit) {
                    usedInWindow++;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void resetWindowIfElapsed(long now) {
            long elapsed = now - windowStartNanos;
            if (elapsed >= windowNanos) {
                // Сдвигаем старт на целое число окон, чтобы не накапливать дрейф
                long windowsPassed = Math.max(1L, elapsed / windowNanos);
                windowStartNanos += windowsPassed * windowNanos;
                usedInWindow = 0;
                nextWindowCond.signalAll();
            }
        }

        private long nanosUntilNextWindow(long now) {
            long elapsed = now - windowStartNanos;
            long remaining = windowNanos - elapsed;
            return remaining > 0 ? remaining : 0L;
        }
    }
}
