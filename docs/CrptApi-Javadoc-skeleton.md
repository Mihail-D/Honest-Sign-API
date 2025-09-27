# CrptApi — Javadoc-скелет и перечень внутренних типов (без кода)

Назначение: готовые Javadoc-блоки и список публичных/внутренних членов для переноса в один файл `CrptApi.java` (без реализации). Скелет описывает потокобезопасность, ограничение частоты (rate limiting) и сетевое взаимодействие.

Важные допущения (уточнить по документации ЧЗ):
- Эндпоинт и JSON-схема для «создания документа для ввода в оборот товара, произведенного в РФ».
- Где передавать подпись (в теле или заголовке), формат подписи (обычно Base64).
- Политики таймаутов/повторов, использование идемпотентности.

---

## 1) Класс и пакет — Javadoc для вставки в `CrptApi.java`

/**
 * Клиент для обращения к API «Честный знак» с поддержкой потокобезопасного
 * ограничения числа запросов (ограничение частоты) и блокирующей семантики,
 * задаваемой в конструкторе через интервал {@link java.util.concurrent.TimeUnit}
 * и максимально допустимое число запросов в этом интервале.
 * <p>
 * Потокобезопасность: экземпляр является thread-safe. Метод отправки запросов
 * может вызываться из нескольких потоков одновременно. При исчерпании лимита
 * новые вызовы будут блокироваться до освобождения окна.
 * <p>
 * Семантика ограничения:
 * <ul>
 *   <li>В любой скользящий интервал времени длиной, равной указанному
 *       {@code timeUnit}, будет выполнено не более {@code requestLimit} запросов.</li>
 *   <li>При достижении лимита новые обращения блокируются до момента, когда
 *       выполнение ещё одного запроса не приведёт к превышению лимита.</li>
 *   <li>Ожидание поддерживает прерывание потока (см. {@link InterruptedException}).</li>
 * </ul>
 * <p>
 * Сетевое взаимодействие:
 * <ul>
 *   <li>Сериализация запроса выполняется в JSON.</li>
 *   <li>HTTP-клиент и сериализатор JSON инкапсулированы и могут быть переопределены
 *       (см. внутренние интерфейсы {@code HttpExecutor}, {@code JsonSerializer}).</li>
 *   <li>Базовый URL, заголовки, таймауты и место передачи подписи настраиваются
 *       через билдер либо расширенный конструктор.</li>
 * </ul>
 * <p>
 * Расширяемость: архитектура предусматривает внутренние интерфейсы и реализации
 * по умолчанию, которые можно заменить без изменения публичного к��нтракта класса.
 * Все дополнительные типы расположены как вложенные (nested) в {@code CrptApi}.
 * <p>
 * Жизненный цикл: в случае наличия внутренних планировщиков/ресурсов предусмотрен
 * метод {@code close()} (реализация {@link java.lang.AutoCloseable}).
 * <p>
 * Ограничения файла: все используемые типы (кроме JDK/зависимостей) должны быть
 * внутренними классами/интерфейсами/enum текущего файла.
 *
 * @author 
 * @since 0.1.0
 */

---

## 2) Конструкторы — Javadoc-сигнатуры (без реализации)

/**
 * Создаёт клиент с ограничением числа запросов: не более {@code requestLimit}
 * за интервал {@code timeUnit}. Экземпляр потокобезопасен.
 *
 * Предусловия:
 * <ul>
 *   <li>{@code timeUnit} не равен {@code null};</li>
 *   <li>{@code requestLimit} > 0;</li>
 * </ul>
 *
 * Поведение при превышении лимита:
 * <ul>
 *   <li>Вызовы методов отправки будут блокироваться до освобождения окна.</li>
 *   <li>Ожидание поддерживает прерывание (см. {@link InterruptedException}).</li>
 * </ul>
 *
 * @param timeUnit интервал окна для ограничения (секунда, минута и т.д.)
 * @param requestLimit максимальное число запросов в указанном интервале (> 0)
 * @throws IllegalArgumentException если {@code requestLimit} <= 0
 * @throws NullPointerException если {@code timeUnit} == null
 */
public CrptApi(java.util.concurrent.TimeUnit timeUnit, int requestLimit) { /* no code */ }

/**
 * Расширенный конструктор/билдер (рекомендуется использовать билдер) для
 * конфигурации HTTP-клиента, сериализации JSON, базового URL, заголовков,
 * таймаутов, стратегии размещения подписи и источника времени.
 *
 * @param builder сконфигурированный билдер клиента
 * @throws IllegalArgumentException если параметры некорректны
 * @see CrptApi.Builder
 */
public CrptApi(CrptApi.Builder builder) { /* no code */ }

---

## 3) Публичный метод API — Javadoc-сигнатуры (без реализации)

/**
 * Создание документа для ввода в оборот товара, произведённого в РФ.
 * <p>
 * Блокирующая семантика: если лимит запросов на интервал исчерпан, вызов будет
 * блокироваться до момента, пока выполнение не станет допустимым согласно
 * ограничениям. Блокировка может быть прервана через {@link Thread#interrupt()}.
 * <p>
 * Сериализация: объект {@code document} сериализуется в JSON. Подпись
 * {@code signature} передаётся согласно текущей стратегии (в заголовке или теле).
 * <p>
 * Сетевое поведение: реализуется через внутренний {@code HttpExecutor};
 * таймауты, повторные попытки и пр. — согласно конфигурации.
 *
 * Контракт ошибок:
 * <ul>
 *   <li>{@link InterruptedException} — поток был прерван во время ожидания разрешения.</li>
 *   <li>{@link CrptApi.CrptApiException} — ошибка сериализации/HTTP/протокола API.</li>
 * </ul>
 *
 * @param document объект доменной модели документа (не null)
 * @param signature строковое представление подписи (например, Base64; не null)
 * @return неизменяемый результат, содержащий HTTP-код, тело и заголовки ответа
 * @throws NullPointerException если {@code document} или {@code signature} равны null
 * @throws InterruptedException при прерывании ожидания в лимитере
 * @throws CrptApi.CrptApiException при ошибках сериализации/HTTP/ответа API
 */
public CrptApi.Result createDocumentForDomesticGoods(Object document, String signature)
        throws InterruptedException, CrptApi.CrptApiException { /* no code */ }

/**
 * Перегрузка с возможностями расширенной конфигурации вызова (например, указание
 * специфичных заголовков, идемпотентного ключа, локальных таймаутов запроса).
 *
 * @param document объект доменной модели
 * @param signature подпись
 * @param options дополнительные параметры конкретного вызова
 * @return результат запроса
 * @throws InterruptedException если ожидание лимита было прервано
 * @throws CrptApi.CrptApiException при ошибках сериализации/HTTP/ответа API
 */
public CrptApi.Result createDocumentForDomesticGoods(Object document,
                                                    String signature,
                                                    CrptApi.CallOptions options)
        throws InterruptedException, CrptApi.CrptApiException { /* no code */ }

---

## 4) Управление ресурсами — Javadoc-сигнатуры

/**
 * Закрывает внутренние ресурсы клиента (если используются фоновые планировщики,
 * пулы подключений и пр.). Повторный вызов безопасен и не приводит к ошибкам.
 *
 * Гарантии: метод не прерывает активные сетевые операции, но предотвращает
 * дальнейшее их создание.
 */
public void close() { /* no code */ }

---

## 5) Внутренние типы (nested) — перечень и Javadoc (без реализации)

### 5.1) Результат вызова

/**
 * Неизменяемый результат HTTP-вызова API Честного знака.
 * Содержит статус ответа, тело (как строку) и заголовки.
 * Может включать идентификатор корреляции/трассировки.
 */
public static final class Result {
    /** HTTP статус-код ответа. */
    public int statusCode;
    /** Тело ответа в виде строки (может быть пустым). */
    public String body;
    /** Заголовки ответа (чувствительность к регистру — по контракту HTTP-клиента). */
    public java.util.Map<String, java.util.List<String>> headers;
}

### 5.2) Опции конкретного вызова

/**
 * Дополнительные опции, применимые к отдельному вызову: локальные таймауты,
 * идемпотентный ключ, дополнительные заголовки и пр.
 */
public static final class CallOptions {
    /** Заголовки сверх глобальных. */
    public java.util.Map<String, String> headers;
    /** Локальный таймаут запроса (если поддерживается HTTP-исполнителем). */
    public java.time.Duration requestTimeout;
    /** Пользовательский идемпотентный ключ (если поддерживается API). */
    public String idempotencyKey;
}

### 5.3) Исключение уровня API

/**
 * Общее исключение клиента API, инкапсулирует ошибки сериализации, сетевых
 * взаимодействий и некорректных ответов сервера.
 */
public static class CrptApiException extends Exception {
    /** Код HTTP-статуса (если доступен). */
    public Integer statusCode;
    /** Низкоуровневая причина (IOException, Timeout и пр.). */
    public Throwable cause;
    /** Тело ответа сервера (если доступно). */
    public String responseBody;
}

### 5.4) Ограничитель запросов (интерфейс)

/**
 * Интерфейс ограничителя (rate limiter) с блокирующей семантикой.
 */
private interface RateLimiter extends AutoCloseable {
    /**
     * Блокирующе ожидает разрешение на выполнение запроса.
     * Может быть прервано через {@link Thread#interrupt()}.
     * @throws InterruptedException если поток прерван во время ожидания
     */
    void acquirePermit() throws InterruptedException;

    /**
     * Освобождает ресурсы лимитера, если таковые имеются.
     */
    @Override
    void close();
}

### 5.5) Реализация лимитера — скользящее окно

/**
 * Реализация скользящего окна: за любой интервал длительностью {@code timeUnit}
 * не более {@code limit} выполненных запросов. Использует справедливую
 * синхронизацию и точные ожидания до момента освобождения окна.
 */
private static final class SlidingWindowRateLimiter implements RateLimiter {
    /** Конструктор: принимает {@code timeUnit}, {@code limit} и {@code clock}. */
    SlidingWindowRateLimiter(java.util.concurrent.TimeUnit timeUnit, int limit, Clock clock) { /* no code */ }
    /** Реализация блокирующей выдачи разрешения. */
    @Override public void acquirePermit() throws InterruptedException { /* no code */ }
    /** Закрытие ресурсов (если есть). */
    @Override public void close() { /* no code */ }
}

### 5.6) Источник времени

/** Источник монотонного времени для детерминированных тестов. */
private interface Clock { long nowNanos(); }

/** Реализация на основе {@link System#nanoTime()}. */
private static final class SystemClock implements Clock { /* no code */ }

### 5.7) Исполнитель HTTP-запросов

/**
 * Абстракция HTTP-исполнителя: формирует, отправляет и получает HTTP-запросы/ответы.
 * Инкапсулирует выбор клиента (java.net.http или другой), таймауты, обработку статусов.
 */
private interface HttpExecutor extends AutoCloseable {
    /**
     * Выполняет HTTP-вызов.
     * @param request подготовленный запрос
     * @return результат ответа (статус, тело, заголовки)
     * @throws CrptApiException в случае сетевой/протокольной ошибки
     */
    Result execute(HttpRequest request) throws CrptApiException;

    /** Закрывает ресурсы HTTP-клиента, если требуется. */
    @Override void close();
}

/** Минимальная модель запроса, независимая от конкретного HTTP-клиента. */
private static final class HttpRequest {
    public String method;          // Например, "POST"
    public java.net.URI uri;       // Полный URL
    public java.util.Map<String, String> headers; // Заголовки
    public String body;            // Строка JSON
    public java.time.Duration timeout; // Локальный таймаут, опционально
}

/** Реализация на основе java.net.http.HttpClient. */
private static final class JavaHttpClientExecutor implements HttpExecutor {
    /** Конструктор принимает конфигурацию таймаутов, версию HTTP и пр. */
    JavaHttpClientExecutor(HttpConfig config) { /* no code */ }
    @Override public Result execute(HttpRequest request) throws CrptApiException { /* no code */ }
    @Override public void close() { /* no code */ }
}

/** Конфигурация HTTP: базовый URL, таймауты, заголовки по умолчанию. */
private static final class HttpConfig {
    public java.net.URI baseUri;
    public java.time.Duration connectTimeout;
    public java.time.Duration readTimeout;
    public java.util.Map<String, String> defaultHeaders;
}

### 5.8) Сериализация JSON

/**
 * Абстракция сериализации JSON для отделения модели от конкретной библиотеки.
 */
private interface JsonSerializer {
    /** Преобразует объект в JSON-строку. */
    String toJson(Object value) throws CrptApiException;
    /** При необходимости — чтение JSON в объект (для ответов). */
    <T> T fromJson(String json, Class<T> type) throws CrptApiException;
}

/** Реализация на Jackson (при использовании внешней зависимости). */
private static final class JacksonJsonSerializer implements JsonSerializer { /* no code */ }

### 5.9) Размещение подписи

/**
 * Стратегия размещения подписи: в заголовке или в теле запроса (JSON-поле).
 */
private enum SignPlacement {
    /** Подпись отправляется в заголовке (имя заголовка настраивается). */ HEADER,
    /** Подпись отправляется в теле запроса (имя поля настраивается). */ BODY
}

### 5.10) Конфигурация клиента (Builder)

/**
 * Билдер для пошаговой конфигурации клиента: базовый URL, таймауты, заголовки,
 * сериализатор, HTTP-исполнитель, политика подписи, источник времени и лимитер.
 */
public static final class Builder {
    /** Указывает базовый URL API. */
    public Builder baseUrl(String url) { return this; }
    /** Добавляет заголовок по умолчанию. */
    public Builder defaultHeader(String name, String value) { return this; }
    /** Таймауты подключения/чтения. */
    public Builder connectTimeout(java.time.Duration d) { return this; }
    public Builder readTimeout(java.time.Duration d) { return this; }
    /** Политика подписи и имена полей/заголовков. */
    public Builder signPlacement(SignPlacement placement) { return this; }
    public Builder signatureHeaderName(String name) { return this; }
    public Builder signatureJsonField(String field) { return this; }
    /** Задание внешних компонентов. */
    public Builder httpExecutor(HttpExecutor executor) { return this; }
    public Builder jsonSerializer(JsonSerializer serializer) { return this; }
    public Builder rateLimiter(RateLimiter limiter) { return this; }
    public Builder clock(Clock clock) { return this; }
    /** Параметры лимита (альтернатива конструктору). */
    public Builder limit(java.util.concurrent.TimeUnit unit, int requests) { return this; }
    /** Финальная сборка клиента. */
    public CrptApi build() { return null; }
}

---

## 6) Вспомогательные константы/настройки

/** Имя заголовка по умолчанию для подписи (если используется HEADER). */
private static final String DEFAULT_SIGNATURE_HEADER = "X-Signature";
/** Имя JSON-поля по умолчанию для подписи (если используется BODY). */
private static final String DEFAULT_SIGNATURE_FIELD = "signature";
/** Путь эндпоинта по умолчанию (уточнить по документации ЧЗ). */
private static final String DEFAULT_CREATE_DOC_PATH = "/api/vX/endpoint";

---

## 7) Примечания по тестируемости (добавить в Javadoc где уместно)
- Лимитер использует монотонное время через `Clock`, что упрощает юнит-тесты.
- Метод `createDocumentForDomesticGoods` декларирует `InterruptedException` — ожидаемая реакция на прерывание в блокирующем ожидании.
- `Result` хранит «сырое» тело ответа и заголовки — это упрощает диагностику и парсинг в вызывающем коде.

---

## 8) Мини-«контракт» метода (для Javadoc)
- Вход:
  - document != null; signature != null/не пустая; при нарушении — NPE/IAE.
- Выход: `Result` с `statusCode`, `body`, `headers`; не null.
- Ошибки: `InterruptedException` (ожидание лимита), `CrptApiException` (сериализация/HTTP/протокол).
- Потокобезопасность: множественные параллельные вызовы; справедливое ожидание.

---

## 9) Псевдо-порядок выполнения `createDocumentForDomesticGoods` (для понимания логики)
1) Вызвать `rateLimiter.acquirePermit()` — возможна блокировка с прерыванием.
2) Подготовить JSON (включая подпись: в header/body согласно политике).
3) Сформировать `HttpRequest` (метод POST, URL = baseUrl + path, заголовки, таймаут).
4) Выполнить запрос через `HttpExecutor` и получить `Result`.
5) Вернуть `Result` вызывающему коду.

---

## 10) Ссылки в Javadoc (куда стоит сослаться)
- {@link java.util.concurrent.TimeUnit}
- {@link java.lang.AutoCloseable}
- {@link InterruptedException}
- {@link System#nanoTime()}
- {@code java.net.http.HttpClient} (если используете его в реализации)

---
