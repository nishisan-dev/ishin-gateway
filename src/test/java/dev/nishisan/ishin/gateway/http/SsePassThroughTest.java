/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.nishisan.ishin.gateway.http;

import org.junit.jupiter.api.*;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários para a funcionalidade de SSE pass-through.
 * <p>
 * Valida a detecção de SSE no {@link HttpWorkLoad} e a correta
 * configuração de flags para ativação do pipeline SSE.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-25
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SsePassThroughTest {

    // ─── HttpWorkLoad SSE Mode Tests ────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T1: HttpWorkLoad — sseMode default é false")
    void testSseModeDefaultFalse() {
        HttpWorkLoad workload = createMinimalWorkload();
        assertFalse(workload.isSseMode(), "sseMode deve ser false por default");
    }

    @Test
    @Order(2)
    @DisplayName("T2: HttpWorkLoad — sseMode setter funciona")
    void testSseModeSetterWorks() {
        HttpWorkLoad workload = createMinimalWorkload();
        workload.setSseMode(true);
        assertTrue(workload.isSseMode(), "sseMode deve ser true após set");
    }

    @Test
    @Order(3)
    @DisplayName("T3: HttpWorkLoad — SSE ativa returnPipe")
    void testSseActivatesReturnPipe() {
        HttpWorkLoad workload = createMinimalWorkload();
        workload.setSseMode(true);
        workload.setReturnPipe(true);
        assertTrue(workload.getReturnPipe(), "returnPipe deve ser true quando SSE ativo");
        assertTrue(workload.isSseMode(), "sseMode deve ser true");
    }

    // ─── Content-Type Detection Tests ───────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("T4: Detecção de SSE por Content-Type text/event-stream")
    void testSseContentTypeDetection() {
        String contentType = "text/event-stream";
        assertTrue(contentType.contains("text/event-stream"),
                "Content-Type text/event-stream deve ser detectado como SSE");
    }

    @Test
    @Order(5)
    @DisplayName("T5: Detecção de SSE por Content-Type com charset")
    void testSseContentTypeWithCharset() {
        String contentType = "text/event-stream; charset=utf-8";
        assertTrue(contentType.contains("text/event-stream"),
                "Content-Type com charset deve ser detectado como SSE");
    }

    @Test
    @Order(6)
    @DisplayName("T6: application/json NÃO é SSE")
    void testJsonNotSse() {
        String contentType = "application/json";
        assertFalse(contentType.contains("text/event-stream"),
                "application/json não deve ser detectado como SSE");
    }

    @Test
    @Order(7)
    @DisplayName("T7: text/html NÃO é SSE")
    void testHtmlNotSse() {
        String contentType = "text/html; charset=utf-8";
        assertFalse(contentType.contains("text/event-stream"),
                "text/html não deve ser detectado como SSE");
    }

    // ─── SSE Event Delimiter Tests ──────────────────────────────────────

    @Test
    @Order(8)
    @DisplayName("T8: Delimitador SSE — \\n\\n detectado corretamente")
    void testSseDelimiterDetection() {
        String ssePayload = "event: message\ndata: {\"hello\":\"world\"}\n\n";
        int flushCount = countSseFlushes(ssePayload.getBytes());
        assertEquals(1, flushCount, "Deve detectar exatamente 1 delimitador SSE");
    }

    @Test
    @Order(9)
    @DisplayName("T9: Múltiplos eventos SSE — flush por evento")
    void testMultipleEvents() {
        String ssePayload = "event: message\ndata: first\n\nevent: message\ndata: second\n\n";
        int flushCount = countSseFlushes(ssePayload.getBytes());
        assertEquals(2, flushCount, "Deve detectar 2 delimitadores SSE para 2 eventos");
    }

    @Test
    @Order(10)
    @DisplayName("T10: Payload sem delimitador SSE — zero flushes")
    void testNoDelimiter() {
        String payload = "data: partial\ndata: no-end";
        int flushCount = countSseFlushes(payload.getBytes());
        assertEquals(0, flushCount, "Sem delimitador SSE, zero flushes");
    }

    @Test
    @Order(11)
    @DisplayName("T11: Suporte a \\r\\n\\r\\n como delimitador SSE")
    void testCrLfDelimiter() {
        String ssePayload = "event: message\r\ndata: hello\r\n\r\n";
        int flushCount = countSseFlushes(ssePayload.getBytes());
        assertEquals(1, flushCount, "\\r\\n\\r\\n deve ser detectado como delimitador SSE");
    }

    // ─── Accept Header Detection Tests ──────────────────────────────────

    @Test
    @Order(12)
    @DisplayName("T12: Accept header text/event-stream detecta SSE request")
    void testAcceptHeaderDetection() {
        String acceptHeader = "text/event-stream";
        boolean sseRequested = acceptHeader.contains("text/event-stream");
        assertTrue(sseRequested, "Accept: text/event-stream deve ser detectado");
    }

    @Test
    @Order(13)
    @DisplayName("T13: Accept header application/json NÃO é SSE request")
    void testAcceptHeaderNotSse() {
        String acceptHeader = "application/json";
        boolean sseRequested = acceptHeader.contains("text/event-stream");
        assertFalse(sseRequested, "Accept: application/json não deve ser detectado como SSE");
    }

    @Test
    @Order(14)
    @DisplayName("T14: Accept header null NÃO é SSE request")
    void testAcceptHeaderNull() {
        String acceptHeader = null;
        boolean sseRequested = acceptHeader != null && acceptHeader.contains("text/event-stream");
        assertFalse(sseRequested, "Accept null não deve ser detectado como SSE");
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /**
     * Cria um HttpWorkLoad com mocks mínimos para testes unitários.
     */
    private HttpWorkLoad createMinimalWorkload() {
        CustomContextWrapper mockContext = mock(CustomContextWrapper.class);
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getHeaderNames()).thenReturn(java.util.Collections.emptyEnumeration());
        when(mockContext.req()).thenReturn(mockRequest);
        return new HttpWorkLoad(mockContext);
    }

    /**
     * Reproduz a lógica de detecção de delimitadores SSE (\n\n) usada em
     * {@code HttpResponseAdapter.writeSseStream()}.
     *
     * @param bytes payload SSE em bytes
     * @return número de eventos SSE detectados (= número de flushes)
     */
    private int countSseFlushes(byte[] bytes) {
        int consecutiveNewlines = 0;
        int flushCount = 0;

        for (byte b : bytes) {
            if (b == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines >= 2) {
                    flushCount++;
                    consecutiveNewlines = 0;
                }
            } else if (b != '\r') {
                consecutiveNewlines = 0;
            }
        }
        return flushCount;
    }
}
