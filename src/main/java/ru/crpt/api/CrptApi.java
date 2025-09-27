package ru.crpt.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
import java.util.function.Supplier;

public final class CrptApi {
    private final RateLimiter rateLimiter;
    private final HttpExecutor httpExecutor;
    private final JsonSerializer json;
    private final HttpConfig httpConfig;
    private final Logger logger;

    private static final String DEFAULT_CREATE_DOC_PATH = "/api/v3/lk/documents/create";

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        Objects.requireNonNull(timeUnit, "единица времени");
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit должен быть > 0");
        }
        this.rateLimiter = new FixedWindowRateLimiter(requestLimit, timeUnit);
        this.httpConfig = HttpConfig.defaults();
        this.httpExecutor = new JavaHttpClientExecutor(httpConfig);
        this.json = new JacksonJsonSerializer();
        this.logger = Logger.noop();
    }

    CrptApi(Builder b) {
        Objects.requireNonNull(b, "builder");
        this.rateLimiter = b.rateLimiter != null ? b.rateLimiter : new FixedWindowRateLimiter(b.limitRequests, b.limitUnit);
        this.httpConfig = b.httpConfig != null ? b.httpConfig : HttpConfig.defaults();
        this.httpExecutor = b.httpExecutor != null ? b.httpExecutor : new JavaHttpClientExecutor(this.httpConfig);
        this.json = b.json != null ? b.json : new JacksonJsonSerializer();
        this.logger = b.logger != null ? b.logger : Logger.noop();
    }

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

    public static final class CreateDocResult {
        public final Result raw;
        public final CreateDocResponse parsed;
        public CreateDocResult(Result raw, CreateDocResponse parsed) {
            this.raw = raw;
            this.parsed = parsed;
        }
    }

    public static final class CallOptions {
        public final Map<String, String> headers;
        public final Duration requestTimeout;
        public final String productGroup;
        public CallOptions(Map<String, String> headers, Duration requestTimeout, String productGroup) {
            this.headers = headers;
            this.requestTimeout = requestTimeout;
            this.productGroup = productGroup;
        }
        public static CallOptions ofProductGroup(String pg) {
            return new CallOptions(null, null, pg);
        }
    }

    public static class CrptApiException extends Exception {
        public final Integer statusCode;
        public final String responseBody;
        public CrptApiException(String message, Throwable cause) { super(message, cause); this.statusCode = null; this.responseBody = null; }
        public CrptApiException(String message, Integer statusCode, String responseBody) { super(message); this.statusCode = statusCode; this.responseBody = responseBody; }
    }
    public static class RateLimitExceededException extends CrptApiException {
        public RateLimitExceededException(String msg, Integer code, String body) { super(msg, code, body); }
    }
    public static class BadRequestException extends CrptApiException {
        public BadRequestException(String msg, Integer code, String body) { super(msg, code, body); }
    }
    public static class AuthenticationException extends CrptApiException {
        public AuthenticationException(String msg, Integer code, String body) { super(msg, code, body); }
    }
    public static class ServerErrorException extends CrptApiException {
        public ServerErrorException(String msg, Integer code, String body) { super(msg, code, body); }
    }
    public static class TimeoutCrptApiException extends CrptApiException {
        public TimeoutCrptApiException(String msg, Throwable cause) { super(msg, cause); }
    }

    @SuppressWarnings({"unused","UnusedReturnValue"})
    public Result createDocumentForDomesticGoods(Object document, String signature)
            throws InterruptedException, CrptApiException {
        return createDocumentForDomesticGoodsParsed(document, signature, null).raw;
    }

    @SuppressWarnings({"unused","UnusedReturnValue"})
    public Result createDocumentForDomesticGoods(Object document, String signature, CallOptions options)
            throws InterruptedException, CrptApiException {
        return createDocumentForDomesticGoodsParsed(document, signature, options).raw;
    }

    public CreateDocResult createDocumentForDomesticGoodsParsed(Object document, String signature, CallOptions options)
            throws InterruptedException, CrptApiException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(signature, "signature");

        acquirePermit();

        try {
            String docJson = json.toJson(document);
            String productDocument = Base64.getEncoder().encodeToString(docJson.getBytes(StandardCharsets.UTF_8));

            CreateDocRequest payload = new CreateDocRequest(
                    "MANUAL",
                    productDocument,
                    options != null ? options.productGroup : null,
                    signature,
                    "LP_INTRODUCE_GOODS"
            );
            String body = json.toJson(payload);

            URI uri = httpConfig.baseUri.resolve(DEFAULT_CREATE_DOC_PATH + buildPgQuerySuffix(options));

            Map<String, String> headers = new HashMap<>(httpConfig.defaultHeaders);
            headers.putIfAbsent("Content-Type", "application/json");
            if (options != null && options.headers != null) headers.putAll(options.headers);

            logger.debug(() -> "POST " + uri + ", headers=" + headers.keySet());

            HttpReq req = new HttpReq("POST", uri, headers, body, options != null ? options.requestTimeout : httpConfig.readTimeout);
            Result raw = httpExecutor.execute(req);

            logger.debug(() -> "Response: status=" + raw.statusCode + ", headersKeys=" + raw.headers.keySet());

            if (raw.statusCode >= 200 && raw.statusCode < 300) {
                CreateDocResponse parsed = null;
                if (raw.body != null && !raw.body.isBlank()) {
                    try {
                        parsed = json.fromJson(raw.body, CreateDocResponse.class);
                    } catch (Exception parseEx) {
                        logger.warn(() -> "Не удалось распарсить тело успешного ответа: " + parseEx.getMessage());
                    }
                }
                return new CreateDocResult(raw, parsed);
            }

            throw mapStatusToException(raw);
        } catch (CrptApiException e) {
            throw e;
        } catch (Exception e) {
            throw new CrptApiException("Ошибка подготовки или выполнения запроса", e);
        }
    }

    private CrptApiException mapStatusToException(Result raw) {
        int sc = raw.statusCode;
        String body = raw.body;
        if (sc == 429) return new RateLimitExceededException("Превышен лимит запросов (429)", sc, body);
        if (sc == 400 || sc == 422) return new BadRequestException("Некорректные данные в запросе (" + sc + ")", sc, body);
        if (sc == 401 || sc == 403) return new AuthenticationException("Ошибка аутентификации/авторизации (" + sc + ")", sc, body);
        if (sc >= 500 && sc <= 599) return new ServerErrorException("Ошибка сервера ЧЗ (" + sc + ")", sc, body);
        return new CrptApiException("Неуспешный статус ответа: " + sc, sc, body);
    }

    private static String buildPgQuerySuffix(CallOptions options) {
        if (options == null || options.productGroup == null || options.productGroup.isBlank()) return "";
        return "?pg=" + options.productGroup;
    }

    void acquirePermit() throws InterruptedException {
        rateLimiter.acquire();
    }

    boolean tryAcquirePermit() {
        return rateLimiter.tryAcquire();
    }

    public interface RateLimiter {
        void acquire() throws InterruptedException;
        boolean tryAcquire();
    }

    public static final class HttpReq {
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

    public interface HttpExecutor extends AutoCloseable {
        Result execute(HttpReq request) throws CrptApiException;
        @Override default void close() { }
    }

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
            } catch (HttpTimeoutException tex) {
                throw new TimeoutCrptApiException("Истек таймаут HTTP-запроса", tex);
            } catch (Exception e) {
                throw new CrptApiException("Ошибка HTTP-вызова", e);
            }
        }
    }

    public interface JsonSerializer {
        String toJson(Object value) throws Exception;
        <T> T fromJson(String json, Class<T> type) throws Exception;
    }

    static final class JacksonJsonSerializer implements JsonSerializer {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper;
        JacksonJsonSerializer() {
            mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.findAndRegisterModules();
            mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        }
        @Override public String toJson(Object value) throws Exception { return mapper.writeValueAsString(value); }
        @Override public <T> T fromJson(String json, Class<T> type) throws Exception { return mapper.readValue(json, type); }
    }

    static final class CreateDocRequest {
        public final String document_format;
        public final String product_document;
        public final String product_group;
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

    public static final class CreateDocResponse { public String value; }

    public static final class Builder {
        private RateLimiter rateLimiter;
        private TimeUnit limitUnit = TimeUnit.SECONDS;
        private int limitRequests = 10;
        private HttpConfig httpConfig;
        private HttpExecutor httpExecutor;
        private JsonSerializer json;
        private Logger logger;

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
        public Builder logger(Logger logger) { this.logger = logger; return this; }
        public Builder rateLimiter(RateLimiter limiter) { this.rateLimiter = limiter; return this; }
        public CrptApi build() { if (httpConfig == null) httpConfig = HttpConfig.defaults(); return new CrptApi(this); }
    }

    static final class FixedWindowRateLimiter implements RateLimiter {
        private final int limit;
        private final long windowNanos;
        private final ReentrantLock lock = new ReentrantLock(true);
        private final Condition nextWindowCond = lock.newCondition();
        private long windowStartNanos;
        private int usedInWindow;

        FixedWindowRateLimiter(int limit, TimeUnit unit) {
            if (limit <= 0) throw new IllegalArgumentException("limit должен быть > 0");
            Objects.requireNonNull(unit, "единица времени");
            this.limit = limit;
            this.windowNanos = unit.toNanos(1L);
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
                        waitNanos = Math.max(50_000L, windowNanos / 100);
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

    public interface Logger {
        void debug(Supplier<String> msg);
        void warn(Supplier<String> msg);
        void error(Supplier<String> msg, Throwable t);
        static Logger noop() { return new Logger() {
            public void debug(Supplier<String> msg) { }
            public void warn(Supplier<String> msg) { }
            public void error(Supplier<String> msg, Throwable t) { }
        }; }
    }
}
