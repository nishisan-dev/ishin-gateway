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
package dev.nishisan.ishin.gateway.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Monta a imagem de integração com o JAR empacotado pelo Maven, sem credenciais
 * ou recompilação da aplicação dentro do Docker.
 */
public final class GatewayTestImage {

    private GatewayTestImage() {
    }

    /**
     * Cria uma imagem de execução a partir do pacote disponível em target.
     *
     * @param name nome da imagem de teste
     * @return imagem contendo o gateway e as regras padrão
     */
    public static ImageFromDockerfile create(String name) {
        Path root = Path.of(System.getProperty("user.dir"));
        try (var files = Files.list(root.resolve("target"))) {
            var jars = files.filter(path -> path.getFileName().toString()
                    .matches("ishin-gateway-.*\\.jar")).toList();
            if (jars.size() != 1) {
                throw new IllegalStateException("Execute mvn package antes dos testes de integração");
            }
            return new ImageFromDockerfile(name, false)
                    .withFileFromPath("app.jar", jars.getFirst())
                    .withFileFromPath("rules", root.resolve("rules"))
                    .withDockerfileFromBuilder(builder -> builder
                            .from("eclipse-temurin:25-jre")
                            .workDir("/app")
                            .copy("app.jar", "app.jar")
                            .copy("rules", "rules")
                            .entryPoint("java", "-XX:+UseZGC", "-Xms128m", "-Xmx256m", "-jar", "app.jar")
                            .build());
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível localizar o JAR do gateway", e);
        }
    }
}
