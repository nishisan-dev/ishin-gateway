# Walkthrough: In-Cluster Benchmark UI

## Resumo das Modificações

Como identificado nos testes locais, executar chamadas de *benchmark* do Host (máquina nativa) apontando para os contêineres mapeados via porta local (`localhost:8080`, `localhost:9091`) introduz na medição o alto overhead de rede provocado pelo processo `docker-proxy` (Network NAT Rules) de roteamento do Docker Desktop / Docker Engine.

Para realizar os benchmarks de Proxy *sem interferências do Sistema Operacional*, elaboramos uma solução nativa: criamos um contêiner chamado `benchmark-ui` equipado com **Python FastAPI**, **Apache Bench (ab)** e uma interface **Web rica em HTML/JS/TailwindCSS**, servida internamente na rede do Compose na porta `8000`.

### Alterações Realizadas

1. **Backend Inteligente (`compose/benchmark-ui/app/main.py`)**:
   - Desenvolvida a rota de API (`/api/run`) capaz de rodar os scripts herdados (`benchmark.py`) dinamicamente via `subprocess`.
   - O aplicativo possui injeção nativa de dependências de Rede: exportamos constantes dinâmicas que substituem o host alvo de `http://localhost:X` pelos *internal DNS labels* nativos do Docker (`http://static-backend:8080`, `http://nginx-proxy:8080`, etc).
2. **Web Frontend Dinâmico e Responsivo**:
   - Elaborada uma UI *Premium* orientada ao moderno padrão *Dark Mode*, contendo seletores de configuração avançada:
     - Alternância visual de Benchmark Controlado (`Fase Fixa`) vs `Fase Sustentada`.
     - Layout contendo estados de Carregamento (*Spinners* textuais).
   - O JS da página captura o resultado RAW (JSON) do pipeline contido no Docker e preenche programaticamente Cards Estilizados ressoando com o CSS nativo usando `Glassmorphism` evidenciando o Throughput (`req/s`) e latências comparativas do Nginx/Javalin nativamente de dento do cluster.
3. **Plumbing de Infraestrutura**:
   - `docker-compose.yml` sofreu o incremento do `benchmark-ui` interligando os demais contêineres pela clausura de `depends_on`.

### Verificação

O ambiente foi recomposto utilizando `docker compose up --build -d` e comprovou-se o êxito testando execuções de tráfego inter-redes via requisições de API sob a nova interface web hospedada em `localhost:8000`. Agora temos duas alternativas isoladas: a capacidade nativa de relatar testes pelo CLI global, e o Portal Web de Diagnóstico isento de Overhead local atuando sobre a Bridge do Host.
