/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.upstream;

import dev.nishisan.ngate.configuration.BackendConfiguration;
import dev.nishisan.ngate.configuration.PassiveHealthCheckConfiguration;
import dev.nishisan.ngate.configuration.StatusCodeRule;
import dev.nishisan.ngate.configuration.UpstreamMemberConfiguration;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link PassiveHealthChecker}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
class PassiveHealthCheckerTest {

    private UpstreamPoolManager poolManager;
    private PassiveHealthChecker checker;
    private static final String BACKEND = "test-api";
    private static final String MEMBER_URL = "http://server1:8080";

    @BeforeEach
    void setUp() {
        poolManager = new UpstreamPoolManager();
        checker = new PassiveHealthChecker();

        // Configura backend com passive health check
        BackendConfiguration backendConfig = new BackendConfiguration();
        backendConfig.setBackendName(BACKEND);
        backendConfig.setMembers(java.util.List.of(
                new UpstreamMemberConfiguration(MEMBER_URL),
                new UpstreamMemberConfiguration("http://server2:8080")
        ));

        // Regra: 503 → 4 ocorrências em 60s
        StatusCodeRule rule503 = new StatusCodeRule();
        rule503.setMaxOccurrences(4);
        rule503.setSlidingWindowSeconds(60);

        // Regra: 502 → 3 ocorrências em 30s
        StatusCodeRule rule502 = new StatusCodeRule();
        rule502.setMaxOccurrences(3);
        rule502.setSlidingWindowSeconds(30);

        PassiveHealthCheckConfiguration phc = new PassiveHealthCheckConfiguration();
        phc.setEnabled(true);
        phc.setStatusCodes(Map.of(503, rule503, 502, rule502));
        phc.setRecoverySeconds(2); // recovery curto para teste

        backendConfig.setPassiveHealthCheck(phc);

        poolManager.initialize(Map.of(BACKEND, backendConfig));
        checker.start(poolManager, Map.of(BACKEND, backendConfig));
    }

    @AfterEach
    void tearDown() {
        checker.stop();
    }

    private UpstreamMemberState getMember(String url) {
        return poolManager.getPool(BACKEND).orElseThrow()
                .getAllMembers().stream()
                .filter(m -> m.getUrl().equals(url))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("T1: status code atinge threshold → marca membro DOWN")
    void statusCodeWithinWindow_marksMemberDown() {
        UpstreamMemberState member = getMember(MEMBER_URL);
        assertTrue(member.isAvailable(), "Membro deve iniciar disponível");

        // 3 ocorrências de 503 — ainda below threshold (4)
        for (int i = 0; i < 3; i++) {
            checker.reportStatusCode(BACKEND, MEMBER_URL, 503);
        }
        assertFalse(member.isPassivelyMarkedDown(), "Ainda abaixo do threshold");

        // 4ª ocorrência — threshold atingido
        checker.reportStatusCode(BACKEND, MEMBER_URL, 503);
        assertTrue(member.isPassivelyMarkedDown(), "Deve estar DOWN após 4 ocorrências de 503");
        assertFalse(member.isAvailable(), "Membro não deve estar disponível");
    }

    @Test
    @DisplayName("T2: status codes diferentes usam janelas independentes")
    void multipleStatusCodes_independentWindows() {
        UpstreamMemberState member = getMember(MEMBER_URL);

        // 2 ocorrências de 502 (threshold = 3)
        checker.reportStatusCode(BACKEND, MEMBER_URL, 502);
        checker.reportStatusCode(BACKEND, MEMBER_URL, 502);

        // 3 ocorrências de 503 (threshold = 4)
        checker.reportStatusCode(BACKEND, MEMBER_URL, 503);
        checker.reportStatusCode(BACKEND, MEMBER_URL, 503);
        checker.reportStatusCode(BACKEND, MEMBER_URL, 503);

        // Nenhum threshold atingido ainda
        assertFalse(member.isPassivelyMarkedDown(), "Nenhum threshold atingido");

        // 3ª ocorrência de 502 → threshold atingido para 502
        checker.reportStatusCode(BACKEND, MEMBER_URL, 502);
        assertTrue(member.isPassivelyMarkedDown(), "Threshold de 502 deve ter sido atingido");
    }

    @Test
    @DisplayName("T3: status 200 não afeta janelas")
    void successfulStatusCode_doesNotAffectWindows() {
        // Reporta muitos 200 — não deve ter efeito
        for (int i = 0; i < 100; i++) {
            checker.reportStatusCode(BACKEND, MEMBER_URL, 200);
        }

        UpstreamMemberState member = getMember(MEMBER_URL);
        assertFalse(member.isPassivelyMarkedDown(), "Status 200 não deve afetar o membro");
        assertTrue(member.isAvailable());
    }

    @Test
    @DisplayName("T4: passive check desabilitado não tem efeito")
    void disabledPassiveCheck_noEffect() {
        checker.stop();

        // Recria com passive disabled
        BackendConfiguration backendConfig = new BackendConfiguration();
        backendConfig.setBackendName("disabled-api");
        backendConfig.setMembers(java.util.List.of(
                new UpstreamMemberConfiguration("http://disabled:8080")
        ));

        PassiveHealthCheckConfiguration phc = new PassiveHealthCheckConfiguration();
        phc.setEnabled(false);
        backendConfig.setPassiveHealthCheck(phc);

        UpstreamPoolManager pm2 = new UpstreamPoolManager();
        pm2.initialize(Map.of("disabled-api", backendConfig));

        PassiveHealthChecker checker2 = new PassiveHealthChecker();
        checker2.start(pm2, Map.of("disabled-api", backendConfig));

        // Reporta muitos erros — deve ser ignorado
        for (int i = 0; i < 100; i++) {
            checker2.reportStatusCode("disabled-api", "http://disabled:8080", 503);
        }

        UpstreamMemberState member = pm2.getPool("disabled-api").orElseThrow()
                .getAllMembers().get(0);
        assertFalse(member.isPassivelyMarkedDown(), "Passive disabled não deve afetar o membro");

        checker2.stop();
    }

    @Test
    @DisplayName("T5: recovery restaura membro após timeout")
    void recoveryAfterTimeout_restoresMemberUp() throws InterruptedException {
        UpstreamMemberState member = getMember(MEMBER_URL);

        // Marca DOWN via threshold
        for (int i = 0; i < 4; i++) {
            checker.reportStatusCode(BACKEND, MEMBER_URL, 503);
        }
        assertTrue(member.isPassivelyMarkedDown(), "Membro deve estar DOWN");

        // Aguarda recovery (2s + margem)
        Thread.sleep(3500);

        assertFalse(member.isPassivelyMarkedDown(), "Membro deve ser restaurado após recovery");
        assertTrue(member.isAvailable(), "Membro deve estar disponível novamente");
    }

    @Test
    @DisplayName("T6: membro diferente não é afetado")
    void differentMember_notAffected() {
        UpstreamMemberState member1 = getMember(MEMBER_URL);
        UpstreamMemberState member2 = getMember("http://server2:8080");

        // Reporta erros apenas no server1
        for (int i = 0; i < 4; i++) {
            checker.reportStatusCode(BACKEND, MEMBER_URL, 503);
        }

        assertTrue(member1.isPassivelyMarkedDown(), "server1 deve estar DOWN");
        assertFalse(member2.isPassivelyMarkedDown(), "server2 não deve ser afetado");
        assertTrue(member2.isAvailable(), "server2 deve continuar disponível");
    }
}
