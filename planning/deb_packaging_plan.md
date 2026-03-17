# Empacotamento .deb do ishin-gateway com GitHub Actions

Empacotar o ishin-gateway como `.deb` para distribuição Linux, com pipeline de CI/CD no GitHub Actions acionado por releases. O pacote segue o FHS e instala o gateway como um serviço systemd.

## Layout FHS do Pacote

| Path instalado | Conteúdo |
|---|---|
| `/opt/ishin-gateway/ishin-gateway.jar` | Fat JAR (Spring Boot) |
| `/etc/ishin-gateway/adapter.yaml` | Configuração principal |
| `/etc/ishin-gateway/rules/default/Rules.groovy` | Scripts de regras Groovy (exemplo) |
| `/etc/ishin-gateway/ssl/` | Keystores (vazio — user popula) |
| `/var/log/ishin-gateway/` | Logs da aplicação |
| `/lib/systemd/system/ishin-gateway.service` | Unit file systemd |
| `/etc/logrotate.d/ishin-gateway` | Rotação de logs |

---

## Proposed Changes

### Git Remote

#### [NEW] Remote GitHub
- Adicionar remote `origin` → `git@github.com:nishisan-dev/ishin-gateway.git`
- Push da branch `main`

---

### Packaging Assets

#### [NEW] [debian/](file:///home/lucas/Projects/ishin-gateway/debian/)

Criar a estrutura Debian control dentro do projeto:

```
debian/
├── control              # Metadados do pacote (nome, versão, deps)
├── conffiles            # Lista de conffiles protegidos
├── postinst             # Script pós-instalação (cria user, dirs, enable service)
├── prerm                # Script pré-remoção (stop service)
├── postrm               # Script pós-remoção (cleanup)
├── ishin-gateway.service       # Systemd unit file
├── ishin-gateway.logrotate     # Logrotate config
└── rules/
    └── default/
        └── Rules.groovy # Exemplo de regra padrão
```

**`debian/control`** — Metadados principais:
```
Package: ishin-gateway
Version: 1.0.0
Architecture: all
Maintainer: Lucas Nishimura <lucas.nishimura@gmail.com>
Depends: default-jre-headless (>= 21) | openjdk-21-jre-headless
Section: net
Priority: optional
Description: ishin-gateway API Gateway
 High-performance reverse proxy and API gateway with
 Groovy-based dynamic routing rules and OAuth2 support.
```

**`debian/conffiles`**:
```
/etc/ishin-gateway/adapter.yaml
/etc/ishin-gateway/rules/default/Rules.groovy
```

**`debian/postinst`**:
```bash
#!/bin/bash
set -e
# Cria user de sistema
if ! id -u ishin-gateway >/dev/null 2>&1; then
    useradd --system --no-create-home --shell /usr/sbin/nologin ishin-gateway
fi
# Cria dirs de runtime
mkdir -p /var/log/ishin-gateway
mkdir -p /etc/ishin-gateway/ssl
chown -R ishin-gateway:ishin-gateway /var/log/ishin-gateway
chown -R ishin-gateway:ishin-gateway /etc/ishin-gateway/ssl
# Habilita e inicia o serviço
systemctl daemon-reload
systemctl enable ishin-gateway.service
```

**`debian/prerm`**:
```bash
#!/bin/bash
set -e
systemctl stop ishin-gateway.service || true
systemctl disable ishin-gateway.service || true
```

**`debian/ishin-gateway.service`**:
```ini
[Unit]
Description=ishin-gateway API Gateway
After=network.target

[Service]
Type=simple
User=ishin-gateway
Group=ishin-gateway
WorkingDirectory=/etc/ishin-gateway
ExecStart=/usr/bin/java \
    -jar /opt/ishin-gateway/ishin-gateway.jar \
    -Dlog4j.configurationFile=/etc/ishin-gateway/log4j2.xml
Restart=on-failure
RestartSec=10
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/var/log/ishin-gateway
ReadOnlyPaths=/etc/ishin-gateway /opt/ishin-gateway

[Install]
WantedBy=multi-user.target
```

---

### GitHub Actions Pipeline

#### [NEW] [release.yml](file:///home/lucas/Projects/ishin-gateway/.github/workflows/release.yml)

Pipeline acionado por `release: [published]`. Etapas:

1. **Build** — `mvn -DskipTests package` com JDK 21.
2. **Package .deb** — Monta a árvore de diretórios FHS e executa `dpkg-deb --build`.
3. **Upload Asset** — Anexa o `.deb` à release via `gh release upload`.

```yaml
name: Build and Package .deb
on:
  release:
    types: [published]
jobs:
  build-deb:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 21
          cache: maven
      - run: mvn -B -DskipTests package
      - name: Build .deb
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          PKG="ishin-gateway_${VERSION}_all"
          # Montar árvore FHS
          mkdir -p "${PKG}/opt/ishin-gateway"
          mkdir -p "${PKG}/etc/ishin-gateway/rules/default"
          mkdir -p "${PKG}/etc/ishin-gateway/ssl"
          mkdir -p "${PKG}/var/log/ishin-gateway"
          mkdir -p "${PKG}/lib/systemd/system"
          mkdir -p "${PKG}/etc/logrotate.d"
          mkdir -p "${PKG}/DEBIAN"
          # Copiar artefatos
          cp target/ishin-gateway-*.jar "${PKG}/opt/ishin-gateway/ishin-gateway.jar"
          cp config/adapter.yaml "${PKG}/etc/ishin-gateway/"
          cp debian/rules/default/Rules.groovy "${PKG}/etc/ishin-gateway/rules/default/"
          cp src/main/resources/log4j2.xml "${PKG}/etc/ishin-gateway/"
          cp debian/ishin-gateway.service "${PKG}/lib/systemd/system/"
          cp debian/ishin-gateway.logrotate "${PKG}/etc/logrotate.d/ishin-gateway"
          # Control files
          sed "s/^Version:.*/Version: ${VERSION}/" debian/control > "${PKG}/DEBIAN/control"
          cp debian/conffiles "${PKG}/DEBIAN/"
          cp debian/postinst "${PKG}/DEBIAN/" && chmod 755 "${PKG}/DEBIAN/postinst"
          cp debian/prerm "${PKG}/DEBIAN/" && chmod 755 "${PKG}/DEBIAN/prerm"
          if [ -f debian/postrm ]; then cp debian/postrm "${PKG}/DEBIAN/" && chmod 755 "${PKG}/DEBIAN/postrm"; fi
          # Build
          dpkg-deb --build "${PKG}"
      - name: Upload .deb to release
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          gh release upload "${GITHUB_REF_NAME}" "ishin-gateway_${VERSION}_all.deb"
```

---

### Ajuste no Código — Path Configurável das Rules via `adapter.yaml`

O path das rules será configurável diretamente no `adapter.yaml` via campo `rulesBasePath`, mantendo toda a configuração centralizada. Default: `"rules"` (compatível com dev).

#### [MODIFY] [EndPointConfiguration.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/configuration/EndPointConfiguration.java)

Adicionar campo `rulesBasePath` com getter/setter:

```diff
  private String ruleMapping;
  private Integer ruleMappingThreads = 1;
+ private String rulesBasePath = "rules";
```

#### [MODIFY] [HttpProxyManager.java](file:///home/lucas/Projects/ishin-gateway/src/main/java/dev/nishisan/ishin/http/HttpProxyManager.java)

Alterar `initGse()` para ler o path da configuração:

```diff
 private void initGse() throws IOException {
-    this.gse = new GroovyScriptEngine("rules");
+    String rulesPath = configuration.getRulesBasePath();
+    this.gse = new GroovyScriptEngine(rulesPath);
     CompilerConfiguration config = this.gse.getConfig();
     config.setRecompileGroovySource(true);
     config.setMinimumRecompilationInterval(60);
-    logger.info("GroovyScriptEngine initialized with recompilation interval: 60s");
+    logger.info("GroovyScriptEngine initialized from [{}] with recompilation interval: 60s", rulesPath);
 }
```

No `adapter.yaml` de produção (empacotado no `.deb`):
```yaml
endpoints:
  default:
    rulesBasePath: "/etc/ishin-gateway/rules"
```

---

## Verification Plan

### Automated Tests

1. **Build do pacote no CI:**
   ```bash
   # Local: simular o build do .deb
   mvn -DskipTests package
   # Montar árvore e verificar que dpkg-deb --build funciona
   ```

2. **Pipeline end-to-end:**
   - Criar release `v1.0.0` via `gh release create v1.0.0 --title "v1.0.0" --notes "Initial .deb release"`
   - Verificar no GitHub Actions que o job `build-deb` executa com sucesso
   - Verificar que o asset `ishin-gateway_1.0.0_all.deb` aparece na release

### Manual Verification (Pós Pipeline)

1. Baixar o `.deb` gerado da release
2. Instalar com `sudo dpkg -i ishin-gateway_*.deb`
3. Verificar que:
   - `/opt/ishin-gateway/ishin-gateway.jar` existe
   - `/etc/ishin-gateway/adapter.yaml` existe
   - `/etc/ishin-gateway/rules/default/Rules.groovy` existe
   - `/var/log/ishin-gateway/` existe com owner `ishin-gateway`
   - `systemctl status ishin-gateway` mostra o serviço como `loaded`
