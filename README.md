# Honest-Sign-API (Честный знак) — учебный клиент

Минимальный потокобезопасный клиент API «Честный знак» с ограничителем запросов, HTTP/JSON и методом создания документа (v3). Все вспомогательные типы реализованы как внутренние классы одного файла `CrptApi.java`.

Требования
- JDK 17+ (в pom настроено требование через Maven Enforcer)
- Maven 3.8+

Быстрый старт: parsed-вариант
Ниже пример вызова метода с получением распарсенного ответа (`value`) и инъекцией Bearer-токена. В примере передаём документ как Map: он будет сериализован в JSON и обёрнут в Base64 для отправки.

```java
import ru.crpt.api.CrptApi;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class Example {
    public static void main(String[] args) throws Exception {
        CrptApi api = new CrptApi.Builder()
                .baseUrl("https://ismp.crpt.ru")
                .authBearer("<ВАШ_ТОКЕН>")
                .limit(TimeUnit.SECONDS, 5) // не более 5 запросов в 1 секунду
                .build();

        Map<String, Object> document = Map.of(
                "owner_inn", "1234567890",
                "doc_id", 42
        );

        // Откреплённая подпись в Base64 (CMS/PKCS#7)
        String signatureBase64 = "<BASE64_ПОДПИСИ>";

        CrptApi.CallOptions opts = CrptApi.CallOptions.ofProductGroup("milk");
        CrptApi.CreateDocResult result = api.createDocumentForDomesticGoodsParsed(document, signatureBase64, opts);

        System.out.println("HTTP статус: " + result.raw.statusCode);
        if (result.parsed != null) {
            System.out.println("ID документа: " + result.parsed.value);
        } else {
            System.out.println("Тело ответа: " + result.raw.body);
        }
    }
}
```

Тонкая настройка заголовков и таймаута (CallOptions)
CallOptions позволяет задать локальные заголовки и таймаут запроса именно для этого вызова. Заголовки из CallOptions перекрывают заголовки, заданные на уровне Builder.defaultHeader(...).

```java
import ru.crpt.api.CrptApi;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

Map<String, String> headers = new HashMap<>();
headers.put("X-Request-Id", "req-123");
headers.put("Content-Type", "application/json"); // при необходимости можно переопределить

CrptApi.CallOptions opts = new CrptApi.CallOptions(
        headers,
        Duration.ofSeconds(5), // локальный таймаут на чтение/ответ
        "milk"                 // product_group (также попадёт в ?pg=)
);

CrptApi.CreateDocResult result = api.createDocumentForDomesticGoodsParsed(document, signatureBase64, opts);
```

Примечания:
- Если указать `requestTimeout` в CallOptions, он перекрывает глобальный `readTimeout` из Builder/HttpConfig.
- Если задать `Content-Type` среди локальных заголовков, он перекрывает значение по умолчанию `application/json`.
- При задании `productGroup` через CallOptions клиент добавит `?pg=...` в URL и продублирует поле `product_group` в теле.

Обработка ошибок
Метод может бросать специализированные исключения (все наследуются от `CrptApi.CrptApiException`):
- `RateLimitExceededException` — 429 Too Many Requests
- `BadRequestException` — 400/422 (ошибка данных)
- `AuthenticationException` — 401/403 (аутентификация/доступ)
- `ServerErrorException` — 5xx (ошибка сервера ЧЗ)
- `TimeoutCrptApiException` — истечение таймаута HTTP-запроса

Пример обработки:
```java
try {
    var r = api.createDocumentForDomesticGoodsParsed(document, signatureBase64, null);
    // ...
} catch (CrptApi.TimeoutCrptApiException e) {
    // retry/policy
} catch (CrptApi.BadRequestException e) {
    // валидация данных: см. e.responseBody
} catch (CrptApi.AuthenticationException e) {
    // проверьте токен/права
} catch (CrptApi.RateLimitExceededException e) {
    // бэкофф/повтор позже
} catch (CrptApi.ServerErrorException e) {
    // повторить позже
} catch (CrptApi.CrptApiException e) {
    // общее сетевое/протокольное
}
```

Логирование: подключение собственного Logger
Клиент поддерживает опциональный лёгкий интерфейс `CrptApi.Logger` с ленивыми сообщениями. Ниже адаптер на `java.util.logging`:
```java
import ru.crpt.api.CrptApi;
import java.util.function.Supplier;

var jul = java.util.logging.Logger.getLogger("HS-API");
CrptApi.Logger logger = new CrptApi.Logger() {
    @Override public void debug(Supplier<String> msg) { if (jul.isLoggable(java.util.logging.Level.FINE)) jul.fine(msg.get()); }
    @Override public void warn(Supplier<String> msg)  { jul.warning(msg.get()); }
    @Override public void error(Supplier<String> msg, Throwable t) { jul.log(java.util.logging.Level.SEVERE, msg.get(), t); }
};

CrptApi api = new CrptApi.Builder()
        .logger(logger)
        .authBearer("<ТОКЕН>")
        .build();
```

Запуск тестов
```bash
mvn -B -DskipITs test
```

Подсказки
- Документ передавайте обычным Java-объектом/картой — клиент сам сериализует его в JSON и обернёт в Base64.
- Для `product_group` используйте `CallOptions.ofProductGroup("milk")` — клиент добавит `?pg=` и поле в тело.
- Чтобы тонко управлять заголовками/таймаутом на один вызов, используйте `new CallOptions(headers, requestTimeout, productGroup)`.
