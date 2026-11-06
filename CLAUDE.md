# Reglas del proyecto ESCÁNER PRO MAX

- El entorno puede reiniciarse en cualquier momento. GUARDA SIEMPRE EL PROGRESO:
  - Mantén `PROGRESO.md` actualizado (estado, tareas en curso, worktrees, commits, peticiones pendientes del usuario) y haz commit + push de la rama en la que trabajas tras cada paso importante.
  - En trabajos largos (agentes, worktrees) haz commits WIP locales frecuentes (cada ~30–40 min) y deja notas en `/tmp/claude-0/bench/notes_<área>.md`.
  - Al empezar o retomar: lee `PROGRESO.md`, las notas del banco y `git worktree list` para continuar donde se dejó.
- Interfaz y comunicación con el usuario en español.
- Los datasets del banco (`/tmp/claude-0/bench/datasets`) son solo para medir: nunca se copian al repo.
- Ramas (desde 2026-11-06):
  - `libre`: rama principal de DESARROLLO. Todo se desarrolla aquí primero. APK directo, sin límites de Google Play
    (id `com.scannerpromax.libre`, `scanner.canal=libre` en gradle.properties).
  - `playstore`: lo que se publica en Google Play (id `com.scannerpromax`, `scanner.canal=playstore`). Se actualiza
    cuando se decida con `git checkout playstore && git merge libre` (gradle.properties no se toca: cada rama conserva
    su canal), quitando lo que Play no permita. Releases en tiempos distintos.
  - `ccr-4d2f0b52-3ktfkq`: rama histórica, ya no se usa.
