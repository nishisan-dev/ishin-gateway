# Rotação de Releases — Manter Apenas as 5 Últimas

## Contexto

O pipeline atual (`release.yml`) dispara nos eventos `release: published` e executa dois jobs: `build-deb` e `docker-publish`. Não há nenhum mecanismo de limpeza de releases antigas. O objetivo é adicionar um job que, ao final do pipeline, remova as releases mais antigas mantendo **apenas as 5 mais recentes**. As **tags** devem ser preservadas.

## Proposta

### [MODIFY] [release.yml](file:///home/lucas/Projects/ishin-gateway/.github/workflows/release.yml)

Adicionar um novo job `cleanup-old-releases` que executa **após** `docker-publish` (usando `needs`). O job irá:

1. Listar todas as releases ordenadas por data (mais recente primeiro) via `gh release list`.
2. Identificar as releases a partir da 6ª posição (excedentes).
3. Deletar cada uma dessas releases com `gh release delete <tag> --yes` **sem** a flag `--cleanup-tag`, garantindo que **somente a release é removida** e a tag permanece intacta.

```yaml
  cleanup-old-releases:
    runs-on: ubuntu-latest
    needs: docker-publish
    permissions:
      contents: write
    steps:
      - name: Delete old releases (keep last 5)
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        run: |
          echo "Listing all releases..."
          gh release list --repo "$GITHUB_REPOSITORY" --limit 100 --json tagName,createdAt \
            --jq 'sort_by(.createdAt) | reverse | .[5:] | .[].tagName' | \
          while read -r tag; do
            echo "Deleting release: ${tag}"
            gh release delete "${tag}" --repo "$GITHUB_REPOSITORY" --yes
          done
          echo "Cleanup complete. Only the 5 most recent releases remain."
```

**Pontos-chave:**
- `gh release delete` **sem** `--cleanup-tag` → remove apenas a release, a tag Git permanece.
- `--limit 100` garante que pegaremos todas as releases existentes.
- Ordenação por `createdAt` em ordem reversa, pegando do índice 5 em diante (`.[5:]`).
- O job roda **após** `docker-publish`, então toda a build e publicação já estarão concluídas.

## Verificação

### Manual
1. Criar uma nova release no repositório (via GitHub CLI ou UI).
2. Verificar que o job `cleanup-old-releases` apareceu no workflow run.
3. Confirmar que no máximo 5 releases permanecem após a execução.
4. Confirmar que as tags das releases removidas **ainda existem** no repositório.
