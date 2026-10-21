Inter (https://rsms.me/inter/) — SIL Open Font License 1.1 (ver OFL.txt).

En `app/src/main/res/font/inter_*.ttf` van 5 pesos estáticos (400, 500, 600, 700, 800) de Google Fonts (v20),
reducidos con fontTools a latín (U+0020-007E, U+00A0-017F, puntuación general, €, ™, flechas, −, ✓) y sin hinting:
~55 KB cada uno, ~134 KB comprimidos en total. Los caracteres que falten se dibujan con la fuente del sistema.

    pyftsubset inter_W.ttf --unicodes="U+0020-007E,U+00A0-017F,U+2010-2027,U+2030-203A,U+20AC,U+2122,U+2190-2193,U+2212,U+2713" \
      --layout-features="kern,liga,calt,ccmp,locl,mark,mkmk,tnum" --no-hinting --desubroutinize
