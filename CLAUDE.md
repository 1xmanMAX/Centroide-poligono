# Reglas del proyecto ESCÁNER PRO MAX

- El entorno puede reiniciarse en cualquier momento. GUARDA SIEMPRE EL PROGRESO:
  - Mantén `PROGRESO.md` actualizado (estado, tareas en curso, worktrees, commits, peticiones pendientes del usuario) y haz commit + push de la rama `ccr-4d2f0b52-3ktfkq` tras cada paso importante.
  - En trabajos largos (agentes, worktrees) haz commits WIP locales frecuentes (cada ~30–40 min) y deja notas en `/tmp/claude-0/bench/notes_<área>.md`.
  - Al empezar o retomar: lee `PROGRESO.md`, las notas del banco y `git worktree list` para continuar donde se dejó.
- Interfaz y comunicación con el usuario en español.
- Los datasets del banco (`/tmp/claude-0/bench/datasets`) son solo para medir: nunca se copian al repo.
