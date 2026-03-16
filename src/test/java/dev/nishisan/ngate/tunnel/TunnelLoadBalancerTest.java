/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.tunnel;

import dev.nishisan.ngate.tunnel.lb.LeastConnectionsBalancer;
import dev.nishisan.ngate.tunnel.lb.RoundRobinBalancer;
import dev.nishisan.ngate.tunnel.lb.WeightedRoundRobinBalancer;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários dos algoritmos de load balancing do Tunnel Mode.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TunnelLoadBalancerTest {

    private BackendMember memberA;
    private BackendMember memberB;
    private BackendMember memberC;

    @BeforeEach
    void setUp() {
        memberA = new BackendMember("nodeA", "10.0.0.1", 9091, "ACTIVE", 100,
                System.currentTimeMillis(), 3);
        memberB = new BackendMember("nodeB", "10.0.0.2", 9091, "ACTIVE", 200,
                System.currentTimeMillis(), 3);
        memberC = new BackendMember("nodeC", "10.0.0.3", 9091, "ACTIVE", 300,
                System.currentTimeMillis(), 3);
    }

    @Test
    @Order(1)
    @DisplayName("T1: RoundRobin distribui sequencialmente entre membros")
    void testRoundRobinDistribution() {
        RoundRobinBalancer lb = new RoundRobinBalancer();
        List<BackendMember> members = List.of(memberA, memberB, memberC);

        // 6 iterações devem produzir 2 ciclos completos
        BackendMember r0 = lb.select(members);
        BackendMember r1 = lb.select(members);
        BackendMember r2 = lb.select(members);
        BackendMember r3 = lb.select(members);
        BackendMember r4 = lb.select(members);
        BackendMember r5 = lb.select(members);

        // Verifica ciclo
        assertSame(r0, r3, "Should cycle back to first member");
        assertSame(r1, r4, "Should cycle back to second member");
        assertSame(r2, r5, "Should cycle back to third member");

        // Verifica que todos foram usados
        assertTrue(List.of(r0, r1, r2).containsAll(List.of(memberA, memberB, memberC)),
                "All members should be selected in one cycle");
    }

    @Test
    @Order(2)
    @DisplayName("T2: LeastConnections seleciona membro com menor contagem de conexões ativas")
    void testLeastConnectionsSelection() {
        LeastConnectionsBalancer lb = new LeastConnectionsBalancer();

        // Simular: A tem 5, B tem 2, C tem 10
        memberA.getActiveConnections().set(5);
        memberB.getActiveConnections().set(2);
        memberC.getActiveConnections().set(10);

        List<BackendMember> members = List.of(memberA, memberB, memberC);

        BackendMember selected = lb.select(members);
        assertSame(memberB, selected, "Should select member with least active connections");

        // Igualar B e verificar que o primeiro com menor count é retornado
        memberB.getActiveConnections().set(5);
        selected = lb.select(members);
        assertEquals(5, selected.getActiveConnections().get(),
                "Should select one of the members with 5 connections");
    }

    @Test
    @Order(3)
    @DisplayName("T3: WeightedRoundRobin distribui proporcionalmente aos pesos")
    void testWeightedRoundRobinDistribution() {
        WeightedRoundRobinBalancer lb = new WeightedRoundRobinBalancer();

        // A: weight=100, B: weight=200 → B deve receber ~2× mais que A
        List<BackendMember> members = List.of(memberA, memberB);

        Map<String, Integer> counts = new ConcurrentHashMap<>();
        int iterations = 300;

        for (int i = 0; i < iterations; i++) {
            BackendMember selected = lb.select(members);
            counts.merge(selected.getNodeId(), 1, Integer::sum);
        }

        int countA = counts.getOrDefault("nodeA", 0);
        int countB = counts.getOrDefault("nodeB", 0);

        // B (weight=200) deve ter ~2× o count de A (weight=100)
        double ratio = (double) countB / countA;
        assertTrue(ratio >= 1.8 && ratio <= 2.2,
                "B/A ratio should be ~2.0 but was " + ratio +
                        " (A=" + countA + ", B=" + countB + ")");
    }

    @Test
    @Order(4)
    @DisplayName("T4: Factory method retorna implementação correta por nome")
    void testFactoryMethod() {
        assertInstanceOf(RoundRobinBalancer.class,
                TunnelLoadBalancer.forAlgorithm("round-robin"));
        assertInstanceOf(LeastConnectionsBalancer.class,
                TunnelLoadBalancer.forAlgorithm("least-connections"));
        assertInstanceOf(WeightedRoundRobinBalancer.class,
                TunnelLoadBalancer.forAlgorithm("weighted-round-robin"));

        // Default para algoritmo desconhecido
        assertInstanceOf(RoundRobinBalancer.class,
                TunnelLoadBalancer.forAlgorithm("unknown-algorithm"));
    }

    @Test
    @Order(5)
    @DisplayName("T5: RoundRobin com membro único sempre retorna o mesmo")
    void testRoundRobinSingleMember() {
        RoundRobinBalancer lb = new RoundRobinBalancer();
        List<BackendMember> members = List.of(memberA);

        for (int i = 0; i < 10; i++) {
            assertSame(memberA, lb.select(members));
        }
    }
}
