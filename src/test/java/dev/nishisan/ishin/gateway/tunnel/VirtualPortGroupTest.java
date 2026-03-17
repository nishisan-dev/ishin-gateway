/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ishin.gateway.tunnel;

import dev.nishisan.ishin.gateway.tunnel.lb.RoundRobinBalancer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link VirtualPortGroup} — lifecycle de membros,
 * filtragem por status e promoção de STANDBY.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VirtualPortGroupTest {

    private VirtualPortGroup group;
    private BackendMember memberA;
    private BackendMember memberB;
    private BackendMember memberC;

    @BeforeEach
    void setUp() {
        group = new VirtualPortGroup(9091, new RoundRobinBalancer());
        memberA = new BackendMember("nodeA", "10.0.0.1", 9091, "ACTIVE", 100,
                System.currentTimeMillis(), 3);
        memberB = new BackendMember("nodeB", "10.0.0.2", 9091, "ACTIVE", 200,
                System.currentTimeMillis(), 3);
        memberC = new BackendMember("nodeC", "10.0.0.3", 9091, "STANDBY", 150,
                System.currentTimeMillis(), 3);
    }

    @Test
    @Order(1)
    @DisplayName("T1: addMember/removeMember lifecycle funciona corretamente")
    void testMemberLifecycle() {
        assertTrue(group.addOrUpdateMember(memberA), "First add should return true (new)");
        assertFalse(group.addOrUpdateMember(memberA), "Second add should return false (update)");
        assertEquals(1, group.getMemberCount());

        group.addOrUpdateMember(memberB);
        assertEquals(2, group.getMemberCount());

        // Remover A — grupo não fica vazio
        assertFalse(group.removeMember(memberA.getKey()), "Group should not be empty");
        assertEquals(1, group.getMemberCount());

        // Remover B — grupo fica vazio
        assertTrue(group.removeMember(memberB.getKey()), "Group should be empty");
        assertEquals(0, group.getMemberCount());
    }

    @Test
    @Order(2)
    @DisplayName("T2: getActiveMembers filtra DRAINING e STANDBY corretamente")
    void testGetActiveMembersFiltering() {
        group.addOrUpdateMember(memberA); // ACTIVE
        group.addOrUpdateMember(memberB); // ACTIVE
        group.addOrUpdateMember(memberC); // STANDBY

        assertEquals(2, group.getActiveMemberCount(), "Should have 2 active members");
        assertEquals(1, group.getStandbyMemberCount(), "Should have 1 standby member");
        assertEquals(3, group.getMemberCount(), "Total should be 3");

        // Marcar B como DRAINING
        memberB.setStatus("DRAINING");
        assertEquals(1, group.getActiveMemberCount(), "Should have 1 active after draining");
        assertEquals(1, group.getDrainingMemberCount(), "Should have 1 draining");
    }

    @Test
    @Order(3)
    @DisplayName("T3: promoteStandby promove STANDBY com maior peso quando zero ACTIVE")
    void testStandbyPromotion() {
        BackendMember standbyLow = new BackendMember("nodeD", "10.0.0.4", 9091, "STANDBY", 50,
                System.currentTimeMillis(), 3);
        BackendMember standbyHigh = new BackendMember("nodeE", "10.0.0.5", 9091, "STANDBY", 300,
                System.currentTimeMillis(), 3);

        group.addOrUpdateMember(standbyLow);
        group.addOrUpdateMember(standbyHigh);

        assertEquals(0, group.getActiveMemberCount(), "No active members");
        assertEquals(2, group.getStandbyMemberCount(), "Two standby members");

        BackendMember promoted = group.promoteStandby();
        assertNotNull(promoted, "Should promote a standby member");
        assertEquals("nodeE", promoted.getNodeId(), "Should promote the highest weight standby");
        assertTrue(promoted.isActive(), "Promoted member should be ACTIVE");
        assertEquals(1, group.getActiveMemberCount(), "Should have 1 active after promotion");
    }

    @Test
    @Order(4)
    @DisplayName("T4: selectNext retorna null quando não há membros ativos")
    void testSelectNextNoActive() {
        group.addOrUpdateMember(memberC); // STANDBY only
        assertNull(group.selectNext(), "Should return null when no active members");
    }

    @Test
    @Order(5)
    @DisplayName("T5: selectNext retorna membro via LB quando há ativos")
    void testSelectNextWithActive() {
        group.addOrUpdateMember(memberA); // ACTIVE
        group.addOrUpdateMember(memberB); // ACTIVE

        BackendMember selected = group.selectNext();
        assertNotNull(selected, "Should select an active member");
        assertTrue(selected.isActive(), "Selected member should be active");
    }

    @Test
    @Order(6)
    @DisplayName("T6: removeMember com key inexistente não causa erro — grupo vazio retorna true (empty)")
    void testRemoveNonexistentMember() {
        // Grupo vazio: removeMember retorna true (isEmpty == true)
        assertTrue(group.removeMember("nonexistent:9091"),
                "Empty group should report empty=true after remove attempt");
    }
}
