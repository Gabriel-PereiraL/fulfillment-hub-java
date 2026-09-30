# Checkpoint de continuidade — nova auditoria adversarial

Uma auditoria adversarial posterior encontrou novos casos reproduzidos F16–F26/F31 e riscos inferidos F27/F28/F30. O plano e o estado ativo ficam em `docs/ADVERSARIAL_REMEDIATION_2026-09-30.md`. O relatório anterior permanece como registro histórico e não representa mais o estado final.

Regressão local final: 44 testes de domínio, 8 unitários de runtime, 14 integrações PostgreSQL/LocalStack, 6 migrations Flyway, stack Compose saudável, E2E até `Delivered`, Kubeconform 15/15 e Gitleaks limpo nos repositórios Java e Sistema-portaria.

Prioridade imediata: corrigir e testar os blockers Java F16–F20/F31, depois F21–F26, e só então tratar os riscos inferidos nas outras stacks e executar regressão completa.

Nunca registrar tokens ou credenciais neste arquivo.
