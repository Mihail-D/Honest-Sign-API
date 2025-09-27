package ru.crpt.api;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class CrptApiDocumentCreationTest {

    static final class CapturingExecutor implements CrptApi.HttpExecutor {
        volatile CrptApi.HttpReq last;
        int status = 200;
        String body = "{\"value\":\"uuid-1\"}";
        @Override
        public CrptApi.Result execute(CrptApi.HttpReq request) {
            this.last = request;
            return new CrptApi.Result(status, body, Map.of());
        }
    }

    @Test
    void createDocument_buildsCorrectRequest_withBase64PayloadAndHeaders() throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("owner_inn", "1234567890");
        doc.put("doc_id", 42);

        CapturingExecutor exec = new CapturingExecutor();
        CrptApi api = new CrptApi.Builder()
                .httpExecutor(exec)
                .contentTypeJson()
                .build();

        CrptApi.Result res = api.createDocumentForDomesticGoods(doc, "base64-signature==");
        assertNotNull(res);
        assertNotNull(exec.last);

        assertEquals("POST", exec.last.method);
        assertTrue(exec.last.uri.toString().endsWith("/api/v3/lk/documents/create"));
        assertEquals("application/json", exec.last.headers.get("Content-Type"));

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.findAndRegisterModules();
        Map<?,?> payload = mapper.readValue(exec.last.body, Map.class);
        assertEquals("MANUAL", payload.get("document_format"));
        assertEquals("LP_INTRODUCE_GOODS", payload.get("type"));
        assertEquals("base64-signature==", payload.get("signature"));

        String pd = Objects.toString(payload.get("product_document"));
        byte[] decoded = Base64.getDecoder().decode(pd);
        String decodedJson = new String(decoded, StandardCharsets.UTF_8);
        Map<?,?> decodedDoc = mapper.readValue(decodedJson, Map.class);
        assertEquals(doc, decodedDoc);
    }

    @Test
    void createDocument_withProductGroup_addsQueryAndBodyField() throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("k1", "v1");

        CapturingExecutor exec = new CapturingExecutor();
        CrptApi api = new CrptApi.Builder()
                .httpExecutor(exec)
                .build();

        CrptApi.CallOptions opts = CrptApi.CallOptions.ofProductGroup("milk");
        api.createDocumentForDomesticGoods(doc, "sig==", opts);

        assertNotNull(exec.last);
        URI uri = exec.last.uri;
        assertTrue(uri.toString().contains("?pg=milk"));

        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<?,?> payload = mapper.readValue(exec.last.body, Map.class);
        assertEquals("milk", payload.get("product_group"));
    }

    @Test
    void createDocument_respectsRateLimit_blocksBetweenCalls() throws Exception {
        CapturingExecutor exec = new CapturingExecutor();
        CrptApi api = new CrptApi.Builder()
                .httpExecutor(exec)
                .limit(java.util.concurrent.TimeUnit.MILLISECONDS, 1)
                .build();

        Map<String, Object> doc = Map.of("a", 1);

        long t0 = System.nanoTime();
        api.createDocumentForDomesticGoods(doc, "s");
        api.createDocumentForDomesticGoods(doc, "s");
        long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue(elapsedMillis >= 1);
    }
}
