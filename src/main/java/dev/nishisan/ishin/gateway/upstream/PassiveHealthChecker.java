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
package dev.nishisan.ishin.gateway.upstream;

import dev.nishisan.ishin.gateway.configuration.BackendConfiguration;
import dev.nishisan.ishin.gateway.configuration.PassiveHealthCheckConfiguration;
import dev.nishisan.ishin.gateway.configuration.StatusCodeRule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passive health checker — monitora as respostas reais do tráfego de
 * produção (status codes) para detectar membros degradados usando
 * sliding windows por status code.
 * <p>
 * Diferente do {@link UpstreamHealthChecker} (ativo/probing), este checker
 * opera passivamente: é notificado pelo {@code HttpProxyManager} após cada
 * resposta upstream e avalia se os thresholds de status codes foram violados.
 * <p>
 * Ciclo de vida:
 * <ul>
 *   <li>{@link #start(UpstreamPoolManager, Map)} — inicializa as janelas</li>
 *   <li>{@link #reportStatusCode(String, String, int)} — notificação por request</li>
 *   <li>{@link #stop()} — para o recovery scheduler</li>
 * </ul>
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
public class PassiveHealthChecker {

    private static final Logger logger = LogManager.getLogger(PassiveHealthChecker.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService recoveryScheduler;
    private ScheduledFuture<?> recoveryTask;

    /**
     * Referência para o pool manager — para acessar os UpstreamMemberState.
     */
    private UpstreamPoolManager poolManager;

    /**
     * Configuração passiva por backend.
     * Key = backendName
     */
    private final Map<String, PassiveHealthCheckConfiguration> configByBackend = new ConcurrentHashMap<>();

    /**
     * Sliding windows organizadas por backend → memberUrl → statusCode.
     */
    private final Map<String, Map<String, Map<Integer, StatusCodeSlidingWindow>>> windows = new ConcurrentHashMap<>();

    /**
     * Inicializa o passive health checker.
     *
     * @param poolManager  referência para o pool manager
     * @param backends     configuração dos backends
     */
    public void start(UpstreamPoolManager poolManager, Map<String, BackendConfiguration> backends) {
        if (running.compareAndSet(false, true)) {
            this.poolManager = poolManager;

            backends.forEach((backendName, config) -> {
                PassiveHealthCheckConfiguration phc = config.getPassiveHealthCheck();
                if (phc == null || !phc.isEnabled() || phc.getStatusCodes().isEmpty()) {
                    logger.debug("Passive health check disabled for backend '{}'", backendName);
                    return;
                }

                configByBackend.put(backendName, phc);

                // Inicializa as janelas para cada membro e cada status code monitorado
                poolManager.getPool(backendName).ifPresent(pool -> {
                    Map<String, Map<Integer, StatusCodeSlidingWindow>> memberWindows = new ConcurrentHashMap<>();
                    for (UpstreamMemberState member : pool.getAllMembers()) {
                        Map<Integer, StatusCodeSlidingWindow> codeWindows = new ConcurrentHashMap<>();
                        for (Map.Entry<Integer, StatusCodeRule> ruleEntry : phc.getStatusCodes().entrySet()) {
                            codeWindows.put(ruleEntry.getKey(),
                                    new StatusCodeSlidingWindow(ruleEntry.getValue().getSlidingWindowSeconds()));
                        }
                        memberWindows.put(member.getUrl(), codeWindows);
                    }
                    windows.put(backendName, memberWindows);

                    logger.info("Passive health check initialized for backend '{}': "
                                    + "monitoring {} status code(s) across {} member(s)",
                            backendName, phc.getStatusCodes().size(), pool.getAllMembers().size());
                });
            });

            // Recovery scheduler — verifica a cada segundo se há membros para restaurar
            this.recoveryScheduler = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofVirtual().name("passive-hc-recovery-", 0).factory());
            this.recoveryTask = recoveryScheduler.scheduleAtFixedRate(
                    this::recoveryCheck, 1, 1, TimeUnit.SECONDS);

            logger.info("PassiveHealthChecker started: {} backend(s) with passive rules",
                    configByBackend.size());
        }
    }

    /**
     * Para o passive health checker e o recovery scheduler.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (recoveryTask != null) {
                recoveryTask.cancel(false);
            }
            if (recoveryScheduler != null) {
                recoveryScheduler.shutdown();
                try {
                    if (!recoveryScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        recoveryScheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    recoveryScheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            windows.clear();
            configByBackend.clear();
            logger.info("PassiveHealthChecker stopped");
        }
    }

    /**
     * Reporta um status code observado no tráfego real.
     * Chamado pelo {@code HttpProxyManager} após cada resposta upstream.
     *
     * @param backendName nome do backend
     * @param memberUrl   URL do membro que respondeu
     * @param statusCode  status code HTTP da resposta
     */
    public void reportStatusCode(String backendName, String memberUrl, int statusCode) {
        if (!running.get()) {
            return;
        }

        PassiveHealthCheckConfiguration phc = configByBackend.get(backendName);
        if (phc == null) {
            return;
        }

        StatusCodeRule rule = phc.getStatusCodes().get(statusCode);
        if (rule == null) {
            return; // status code não monitorado
        }

        Map<String, Map<Integer, StatusCodeSlidingWindow>> memberWindows = windows.get(backendName);
        if (memberWindows == null) {
            return;
        }

        Map<Integer, StatusCodeSlidingWindow> codeWindows = memberWindows.get(memberUrl);
        if (codeWindows == null) {
            return;
        }

        StatusCodeSlidingWindow window = codeWindows.get(statusCode);
        if (window == null) {
            return;
        }

        long now = System.currentTimeMillis();
        window.record(now);
        int currentCount = window.count();

        logger.debug("Passive HC '{}' member {}: status {} → {}/{} in window",
                backendName, memberUrl, statusCode, currentCount, rule.getMaxOccurrences());

        if (currentCount >= rule.getMaxOccurrences()) {
            // Threshold violado — marcar membro como passivamente DOWN
            Optional<UpstreamPool> poolOpt = poolManager.getPool(backendName);
            if (poolOpt.isPresent()) {
                for (UpstreamMemberState member : poolOpt.get().getAllMembers()) {
                    if (member.getUrl().equals(memberUrl) && !member.isPassivelyMarkedDown()) {
                        member.markPassivelyUnhealthy();
                        window.clear(); // reset para evitar re-trigger imediato na recovery
                        logger.warn("⬇ Passive HC: backend '{}' member {} marked DOWN "
                                        + "(status {} reached {}/{} in {}s window)",
                                backendName, memberUrl, statusCode,
                                currentCount, rule.getMaxOccurrences(),
                                rule.getSlidingWindowSeconds());
                        break;
                    }
                }
            }
        }
    }

    /**
     * Verifica periodicamente se há membros passivamente DOWN que devem ser
     * restaurados após o recoverySeconds.
     */
    private void recoveryCheck() {
        long now = System.currentTimeMillis();

        configByBackend.forEach((backendName, phc) -> {
            long recoveryMs = phc.getRecoverySeconds() * 1000L;
            poolManager.getPool(backendName).ifPresent(pool -> {
                for (UpstreamMemberState member : pool.getAllMembers()) {
                    if (member.isPassivelyMarkedDown()) {
                        long downSince = member.getPassiveDownSince();
                        if (downSince > 0 && (now - downSince) >= recoveryMs) {
                            member.markPassivelyHealthy();
                            // Limpa as janelas do membro para recomeçar a contagem
                            Map<String, Map<Integer, StatusCodeSlidingWindow>> memberWindows = windows.get(backendName);
                            if (memberWindows != null) {
                                Map<Integer, StatusCodeSlidingWindow> codeWindows = memberWindows.get(member.getUrl());
                                if (codeWindows != null) {
                                    codeWindows.values().forEach(StatusCodeSlidingWindow::clear);
                                }
                            }
                            logger.info("⬆ Passive HC: backend '{}' member {} restored UP "
                                            + "(after {}s recovery period)",
                                    backendName, member.getUrl(), phc.getRecoverySeconds());
                        }
                    }
                }
            });
        });
    }

    public boolean isRunning() {
        return running.get();
    }
}
