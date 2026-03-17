/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ishin.gateway.upstream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link StatusCodeSlidingWindow}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
class StatusCodeSlidingWindowTest {

    @Test
    @DisplayName("T1: record e count dentro da janela")
    void recordAndCount_withinWindow() {
        StatusCodeSlidingWindow window = new StatusCodeSlidingWindow(60);
        long now = System.currentTimeMillis();

        window.record(now);
        window.record(now + 100);
        window.record(now + 200);

        assertEquals(3, window.count());
    }

    @Test
    @DisplayName("T2: eviction remove entradas expiradas")
    void eviction_removesExpiredEntries() {
        StatusCodeSlidingWindow window = new StatusCodeSlidingWindow(10); // 10s
        long now = System.currentTimeMillis();

        // Registra 3 eventos "antigos" (15 segundos atrás)
        window.record(now - 15_000);
        window.record(now - 14_000);
        window.record(now - 13_000);

        // Registra 2 eventos recentes
        window.record(now - 5_000);
        window.record(now);

        // Apenas os 2 recentes devem estar na janela
        assertEquals(2, window.count());
    }

    @Test
    @DisplayName("T3: clear remove todos os registros")
    void clear_removesAll() {
        StatusCodeSlidingWindow window = new StatusCodeSlidingWindow(60);
        long now = System.currentTimeMillis();

        window.record(now);
        window.record(now + 100);
        assertEquals(2, window.count());

        window.clear();
        assertEquals(0, window.count());
    }

    @Test
    @DisplayName("T4: acesso concorrente é thread-safe")
    void concurrentAccess_threadSafe() throws InterruptedException {
        StatusCodeSlidingWindow window = new StatusCodeSlidingWindow(60);
        int threadCount = 10;
        int recordsPerThread = 100;

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> {
                for (int j = 0; j < recordsPerThread; j++) {
                    window.record(System.currentTimeMillis());
                }
            });
        }

        for (Thread t : threads) {
            t.join();
        }

        // Todos os registros devem estar na janela (window de 60s, todos registrados agora)
        assertEquals(threadCount * recordsPerThread, window.count());
    }

    @Test
    @DisplayName("T5: janela vazia retorna count 0")
    void emptyWindow_returnsZero() {
        StatusCodeSlidingWindow window = new StatusCodeSlidingWindow(60);
        assertEquals(0, window.count());
    }

    @Test
    @DisplayName("T6: getWindowMs retorna valor correto")
    void getWindowMs_returnsCorrectValue() {
        StatusCodeSlidingWindow window = new StatusCodeSlidingWindow(30);
        assertEquals(30_000L, window.getWindowMs());
    }
}
