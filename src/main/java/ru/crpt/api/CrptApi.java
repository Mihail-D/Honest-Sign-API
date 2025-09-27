package ru.crpt.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Клиент API Честного знака.
 * На данном этапе реализовано ограничение количества
 * запросов в фиксированном временном окне (1 единица {@link TimeUnit}).
 * Добавлены поддержка HTTP/JSON и каркас метода создания документа.
 * Будущие сетевые методы обязаны вызывать {@link #acquirePermit()} перед
 * обращением к удаленному API.
 */
public final class CrptApi {
    /** Внутренний лимитер запросов. */
    private final RateLimiter rateLimiter;

    /** HTTP-исполнитель. */
    private final HttpExecutor httpExecutor;
    /** JSON-сериализатор. */
    private final JsonSerializer json;
    /** Конфигурация HTTP. */
    private final HttpConfig httpConfig;

    /** Путь эндпоинта по умолчанию для создания документа. */
    private static final String DEFAULT_CREATE_DOC_PATH = "/api/v3/lk/documents/create";

    /**
     * Создает инстанс API клиента с ограничением количества запросов в интервале.
     * Используются значения по умолчанию для HTTP/JSON: HttpClient и Jackson.
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
        this.httpConfig = HttpConfig.defaults();
        this.httpExecutor = new JavaHttpClientExecutor(httpConfig);
        this.json = new JacksonJsonSerializer();
    }

    /**
     * Конструктор из билдера для расширенной конфигурации.
     */
    CrptApi(Builder b) {
        Objects.requireNonNull(b, "builder");
        this.rateLimiter = b.rateLimiter != null ? b.rateLimiter : new FixedWindowRateLimiter(b.limitRequests, b.limitUnit);
        this.httpConfig = b.httpConfig != null ? b.httpConfig : HttpConfig.defaults();
        this.httpExecutor = b.httpExecutor != null ? b.httpExecutor : new JavaHttpClientExecutor(this.httpConfig);
        this.json = b.json != null ? b.json : new JacksonJsonSerializer();
    }

    // ------------------- ПУБЛИЧНЫЕ МЕТОДЫ API -------------------

    /** Результат HTTP-вызова. */
    public static final class Result {
        public final int statusCode;
        public final String body;
        public final Map<String, List<String>> headers;
        public Result(int statusCode, String body, Map<String, List<String>> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers == null ? Map.of() : headers;
        }
    }

    /** Опции вызова. */
    public static final class CallOptions {
        public final Map<String, String> headers;
        public final Duration requestTimeout;
        public final String productGroup; // альтернативно pg в query
        public CallOptions(Map<String, String> headers, Duration requestTimeout, String productGroup) {
            this.headers = headers;
            this.requestTimeout = requestTimeout;
            this.productGroup = productGroup;
        }
        public static CallOptions ofProductGroup(String pg) {
            return new CallOptions(null, null, pg);
        }
    }

    /** Общее исключение уровня клиента. */
    public static class CrptApiException extends Exception {
        public final Integer statusCode;
        public final String responseBody;
        public CrptApiException(String message, Throwable cause) { super(message, cause); this.statusCode = null; this.responseBody = null; }
        public CrptApiException(String message, Integer statusCode, String responseBody) { super(message); this.statusCode = statusCode; this.responseBody = responseBody; }
    }

    /**
     * Создание документа для ввода в оборот товара, произведённого в РФ.
     * Каркас: выполняется ограничение по частоте, подготовка JSON и HTTP-запроса.
     *
     * @param document  объект документа доменной модели (будет сериализован в JSON и обёрнут в Base64)
     * @param signature откреплённая подпись (УКЭП, CMS/PKCS#7) в Base64
     * @return результат HTTP-вызова
     * @throws NullPointerException  если аргументы null
     * @throws InterruptedException  при прерывании ожидания разрешения лимитером
     * @throws CrptApiException      при ошибках сериализации/HTTP
     */
    public Result createDocumentForDomesticGoods(Object document, String signature)
            throws InterruptedException, CrptApiException {
        return createDocumentForDomesticGoods(document, signature, null);
    }

    /** Перегрузка с опциями вызова. См. описание основного метода. */
    public Result createDocumentForDomesticGoods(Object document, String signature, CallOptions options)
            throws InterruptedException, CrptApiException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(signature, "signature");

        // 1) Ограничение частоты
        acquirePermit();

        try {
            // 2) Сериализуем исходный объект документа в JSON и кодируем Base64
            String docJson = json.toJson(document);
            String productDocument = Base64.getEncoder().encodeToString(docJson.getBytes(StandardCharsets.UTF_8));

            // 3) Формируем полезную нагрузку запроса
            CreateDocRequest payload = new CreateDocRequest(
                    "MANUAL", // формат: JSON
                    productDocument,
                    options != null ? options.productGroup : null,
                    signature,
                    "LP_INTRODUCE_GOODS"
            );
            String body = json.toJson(payload);

            // 4) Строим URL: baseUri + path + optional ?pg=
            URI uri = httpConfig.baseUri.resolve(DEFAULT_CREATE_DOC_PATH + buildPgQuerySuffix(options));

            // 5) Заголовки: объединяем дефолтные + опциональные из CallOptions
            Map<String, String> headers = new HashMap<>(httpConfig.defaultHeaders);
            headers.putIfAbsent("Content-Type", "application/json");
            if (options != null && options.headers != null) headers.putAll(options.headers);

            // 6) Выполняем HTTP
            HttpReq req = new HttpReq("POST", uri, headers, body, options != null ? options.requestTimeout : httpConfig.readTimeout);
            return httpExecutor.execute(req);
        } catch (CrptApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CrptApiException("Ошибка подготовки или выполнения запроса", e);
        }
    }

    private static String buildPgQuerySuffix(CallOptions options) {
        if (options == null || options.productGroup == null || options.productGroup.isBlank()) return "";
        return "?pg=" + options.productGroup;
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

    // ------------------- ВНУТРЕННИЕ ТИПЫ: HTTP/JSON/DTO -------------------

    /** Простейший контракт лимитера. */
    interface RateLimiter {
        void acquire() throws InterruptedException;
        boolean tryAcquire();
    }

    /** HTTP запрос для внутреннего исполнителя. */
    static final class HttpReq {
        final String method;
        final URI uri;
        final Map<String, String> headers;
        final String body;
        final Duration timeout;
        HttpReq(String method, URI uri, Map<String, String> headers, String body, Duration timeout) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
            this.body = body;
            this.timeout = timeout;
        }
    }

    /** Конфигурация HTTP. */
    static final class HttpConfig {
        final URI baseUri;
        final Duration connectTimeout;
        final Duration readTimeout;
        final Map<String, String> defaultHeaders;
        HttpConfig(URI baseUri, Duration connectTimeout, Duration readTimeout, Map<String, String> defaultHeaders) {
            this.baseUri = baseUri;
            this.connectTimeout = connectTimeout;
            this.readTimeout = readTimeout;
            this.defaultHeaders = defaultHeaders == null ? Map.of() : Map.copyOf(defaultHeaders);
        }
        static HttpConfig defaults() {
            return new HttpConfig(
                    URI.create("https://ismp.crpt.ru"),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(30),
                    Map.of()
            );
        }
    }

    /** Интерфейс HTTP-исполнителя. */
    interface HttpExecutor extends AutoCloseable {
        Result execute(HttpReq request) throws CrptApiException;
        @Override default void close() { /* no-op */ }
    }

    /** Реализация на java.net.http.HttpClient. */
    static final class JavaHttpClientExecutor implements HttpExecutor {
        private final HttpClient client;
        private final HttpConfig cfg;
        JavaHttpClientExecutor(HttpConfig cfg) {
            this.cfg = cfg;
            this.client = HttpClient.newBuilder()
                    .connectTimeout(cfg.connectTimeout)
                    .build();
        }
        @Override
        public Result execute(HttpReq r) throws CrptApiException {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder()
                        .uri(r.uri)
                        .timeout(r.timeout != null ? r.timeout : cfg.readTimeout);
                if ("POST".equalsIgnoreCase(r.method)) {
                    b = b.POST(HttpRequest.BodyPublishers.ofString(r.body != null ? r.body : ""));
                } else if ("GET".equalsIgnoreCase(r.method)) {
                    b = b.GET();
                } else if ("DELETE".equalsIgnoreCase(r.method)) {
                    b = b.DELETE();
                } else if ("PUT".equalsIgnoreCase(r.method)) {
                    b = b.PUT(HttpRequest.BodyPublishers.ofString(r.body != null ? r.body : ""));
                } else {
                    throw new IllegalArgumentException("Неподдерживаемый метод: " + r.method);
                }
                if (r.headers != null) {
                    r.headers.forEach(b::header);
                }
                HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new Result(resp.statusCode(), resp.body(), resp.headers().map());
            } catch (Exception e) {
                throw new CrptApiException("Ошибка HTTP-вызова", e);
            }
        }
    }

    /** Интерфейс сериализации JSON. */
    interface JsonSerializer {
        String toJson(Object value) throws Exception;
        <T> T fromJson(String json, Class<T> type) throws Exception;
    }

    /** Реализация на Jackson. */
    static final class JacksonJsonSerializer implements JsonSerializer {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;
        JacksonJsonSerializer() {
            mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules(); // jsr310 и т.п.
            mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        }
        @Override public String toJson(Object value) throws Exception { return mapper.writeValueAsString(value); }
        @Override public <T> T fromJson(String json, Class<T> type) throws Exception { return mapper.readValue(json, type); }
    }

    /** DTO: тело запроса создания документа. */
    static final class CreateDocRequest {
        public final String document_format;
        public final String product_document;
        public final String product_group; // опционально
        public final String signature;
        public final String type;
        CreateDocRequest(String document_format, String product_document, String product_group, String signature, String type) {
            this.document_format = document_format;
            this.product_document = product_document;
            this.product_group = product_group;
            this.signature = signature;
            this.type = type;
        }
    }

    /** DTO: успешный ответ создания документа. */
    static final class CreateDocResponse { public String value; }

    // ------------------- BUILDER -------------------

    public static final class Builder {
        private RateLimiter rateLimiter;
        private TimeUnit limitUnit = TimeUnit.SECONDS;
        private int limitRequests = 10;
        private HttpConfig httpConfig;
        private HttpExecutor httpExecutor;
        private JsonSerializer json;

        public Builder limit(TimeUnit unit, int requests) { this.limitUnit = Objects.requireNonNull(unit); this.limitRequests = requests; return this; }
        public Builder baseUrl(String baseUrl) {
            HttpConfig base = httpConfig == null ? HttpConfig.defaults() : httpConfig;
            this.httpConfig = new HttpConfig(URI.create(baseUrl), base.connectTimeout, base.readTimeout, base.defaultHeaders);
            return this;
        }
        public Builder defaultHeader(String name, String value) {
            HttpConfig base = httpConfig == null ? HttpConfig.defaults() : httpConfig;
            Map<String,String> map = new HashMap<>(base.defaultHeaders);
            map.put(name, value);
            this.httpConfig = new HttpConfig(base.baseUri, base.connectTimeout, base.readTimeout, map);
            return this;
        }
        public Builder authBearer(String token) { return defaultHeader("Authorization", "Bearer " + token); }
        public Builder contentTypeJson() { return defaultHeader("Content-Type", "application/json"); }
        public Builder timeouts(Duration connect, Duration read) {
            HttpConfig base = httpConfig == null ? HttpConfig.defaults() : httpConfig;
            this.httpConfig = new HttpConfig(base.baseUri, connect, read, base.defaultHeaders);
            return this;
        }
        public Builder httpExecutor(HttpExecutor exec) { this.httpExecutor = exec; return this; }
        public Builder json(JsonSerializer serializer) { this.json = serializer; return this; }
        public Builder rateLimiter(RateLimiter limiter) { this.rateLimiter = limiter; return this; }
        public CrptApi build() { if (httpConfig == null) httpConfig = HttpConfig.defaults(); return new CrptApi(this); }
    }

    // ------------------- RATE LIMITER -------------------

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
