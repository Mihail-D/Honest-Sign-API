package ru.crpt.api;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiErrorAndParsingTest {

    static final class StaticResultExecutor implements CrptApi.HttpExecutor {
        final CrptApi.Result resultToReturn;
        StaticResultExecutor(int code, String body) {
            this.resultToReturn = new CrptApi.Result(code, body, Map.of());
        }
        @Override public CrptApi.Result execute(CrptApi.HttpReq request) { return resultToReturn; }
    }

    static final class ThrowingTimeoutExecutor implements CrptApi.HttpExecutor {
        @Override public CrptApi.Result execute(CrptApi.HttpReq request) throws CrptApi.CrptApiException {
            throw new CrptApi.TimeoutCrptApiException("timeout", new RuntimeException("simulated"));
        }
    }

    @Test
    void parsed_success_response_isReturned() throws Exception {
        var exec = new StaticResultExecutor(200, "{\"value\":\"doc-" + UUID.randomUUID() + "\"}");
        var api = new CrptApi.Builder().httpExecutor(exec).build();

        var res = api.createDocumentForDomesticGoodsParsed(Map.of("x", 1), "sig==", null);
        assertNotNull(res);
        assertNotNull(res.raw);
        assertEquals(200, res.raw.statusCode);
        assertNotNull(res.parsed);
        assertNotNull(res.parsed.value);
        assertTrue(res.parsed.value.startsWith("doc-"));
    }

    @Test
    void error_mapping_rateLimit_429() {
        var api = new CrptApi.Builder()
                .httpExecutor(new StaticResultExecutor(429, "too many"))
                .limit(TimeUnit.SECONDS, 100)
                .build();
        assertThrows(CrptApi.RateLimitExceededException.class, () ->
                api.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
    }

    @Test
    void error_mapping_badRequest_400_and_422() {
        var api400 = new CrptApi.Builder().httpExecutor(new StaticResultExecutor(400, "bad"))
                .limit(TimeUnit.SECONDS, 100).build();
        var api422 = new CrptApi.Builder().httpExecutor(new StaticResultExecutor(422, "bad"))
                .limit(TimeUnit.SECONDS, 100).build();
        assertThrows(CrptApi.BadRequestException.class, () ->
                api400.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
        assertThrows(CrptApi.BadRequestException.class, () ->
                api422.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
    }

    @Test
    void error_mapping_auth_401_403() {
        var api401 = new CrptApi.Builder().httpExecutor(new StaticResultExecutor(401, "unauth"))
                .limit(TimeUnit.SECONDS, 100).build();
        var api403 = new CrptApi.Builder().httpExecutor(new StaticResultExecutor(403, "forbidden"))
                .limit(TimeUnit.SECONDS, 100).build();
        assertThrows(CrptApi.AuthenticationException.class, () ->
                api401.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
        assertThrows(CrptApi.AuthenticationException.class, () ->
                api403.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
    }

    @Test
    void error_mapping_server_5xx() {
        var api = new CrptApi.Builder().httpExecutor(new StaticResultExecutor(502, "bad gateway"))
                .limit(TimeUnit.SECONDS, 100).build();
        assertThrows(CrptApi.ServerErrorException.class, () ->
                api.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
    }

    @Test
    void timeout_exception_propagates() {
        var api = new CrptApi.Builder().httpExecutor(new ThrowingTimeoutExecutor())
                .limit(TimeUnit.SECONDS, 100).build();
        assertThrows(CrptApi.TimeoutCrptApiException.class, () ->
                api.createDocumentForDomesticGoodsParsed(Map.of("a",1), "s==", null)
        );
    }

    @Test
    void null_arguments_throw_NPE() {
        var api = new CrptApi.Builder().httpExecutor(new StaticResultExecutor(200, "{}"))
                .limit(TimeUnit.SECONDS, 100).build();
        assertThrows(NullPointerException.class, () -> api.createDocumentForDomesticGoodsParsed(null, "s==", null));
        assertThrows(NullPointerException.class, () -> api.createDocumentForDomesticGoodsParsed(Map.of("x",1), null, null));
    }

    @RepeatedTest(10)
    void property_based_random_document_encoded_as_base64_json() throws Exception {
        Random rnd = new Random();
        Map<String, Object> doc = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            String key = "k" + rnd.nextInt(1000);
            Object val = (i % 2 == 0) ? rnd.nextInt(10_000) : UUID.randomUUID().toString();
            doc.put(key, val);
        }

        class CaptureExec implements CrptApi.HttpExecutor {
            volatile CrptApi.HttpReq last;
            @Override public CrptApi.Result execute(CrptApi.HttpReq request) {
                last = request;
                return new CrptApi.Result(200, "{}", Map.of());
            }
        }
        CaptureExec exec = new CaptureExec();
        var api = new CrptApi.Builder().httpExecutor(exec).limit(TimeUnit.SECONDS, 100).build();

        api.createDocumentForDomesticGoods(doc, "sig==");
        assertNotNull(exec.last);

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<?,?> payload = mapper.readValue(exec.last.body, Map.class);
        String pd = (String) payload.get("product_document");
        String decodedJson = new String(Base64.getDecoder().decode(pd), StandardCharsets.UTF_8);
        Map<?,?> decoded = mapper.readValue(decodedJson, Map.class);
        assertEquals(doc, decoded, "Декодированный JSON должен совпадать с исходным документом");
    }
}
