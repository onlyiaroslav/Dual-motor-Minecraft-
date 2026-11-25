# Texture Pack Consigliati per Starling (Android)

Pack leggeri e compatibili con le ottimizzazioni di `starling-features`
(A2C/dithered alpha, foglie hollow, greedy meshing, baking statico).
Criteri: risoluzione ≤ 16x per VRAM mobile, niente dipendenze da OptiFine/CTM
se il motore non le supporta, nessuna modifica a stati di blocchi o redstone
(le texture non toccano la logica, ma pack con modelli custom pesanti vanificano
il greedy meshing).

> Nota: verifica disponibilità e licenza di ciascun pack prima della
> distribuzione; i link cambiano nel tempo. Questa lista è curata per
> compatibilità, non è un endorsement.

## Consigliati (leggeri, mobile-first)

| Pack | Risoluzione | Perché è compatibile |
|---|---|---|
| **Vanilla Tweaks** (opzione *Bushy Leaves*) | 16x | Foglie "bushy" via modelli: perfette con A2C/dithered; il modulo hollow cula le interne senza toccare decadimento/luce. Scegli solo i moduli che ti servono. |
| **Bare Bones** | 16x | Texture piatte e semplificate, costo di campionamento minimo; ideale con greedy meshing (facce unite → meno draw). |
| **F8thful** | 8x | Fedele a vanilla ma 8x: VRAM ridottissima, ottimo per device di fascia bassa. |
| **Faithful 32x** | 32x | Fedele ad alta risoluzione; usare solo su device recenti (VRAM). Compatibile con tutte le ottimizzazioni. |
| **Pack cel-shading / toon** (es. stile Borderlands) | 16x | Texture piatte in stile cel-shading: si sposano bene con A2C e mesh greedy; verifica che non richiedano shader OptiFine. |
| **"FancyFast Bushy Leaves"** (citato nelle specifiche) | 16x | Nome indicato come esempio: verifica il pack effettivo disponibile; in generale i pack bushy-leaves sono compatibili con hollow/A2C. |

## Compatibilità con le ottimizzazioni

- **Foglie (A2C / dithered / hollow)**: i pack con foglie trasparenti o bushy
  funzionano in tutte le modalità. In `hollow` le foglie interne non vengono
  meshate: il pack non deve assumere facce interne visibili. Il random tick
  (decadimento) e la luce restano vanilla con qualunque pack.
- **Greedy meshing**: pack con texture uniformi (Bare Bones, Faithful) massimizzano
  il beneficio visivo delle facce unite. Redstone, casse, hopper, colture e
  farmland restano sempre quad separati: nessun pack può romperne la logica.
- **Baking statico (casse/shulker)**: i pack che ridisegnano le casse funzionano
  sia con mesh baked (chiuse) sia dinamica (apertura/pistone): l'invalidazione
  è same-frame, indipendente dalla texture.
- **X-Ray**: è una funzione del motore, non del pack; i pack "x-ray texture"
  sono superflui e spesso più pesanti. Vale l'avviso: solo singleplayer/server
  privati.

## pack_format (riferimento rapido)

Il `pack_format` corretto va impostato in `pack.mcmeta`; l'importatore lo
genera/corregge automaticamente (`TexturePackImporter.PACK_FORMAT`).

| Versione Minecraft | pack_format (risorse) |
|---|---|
| 1.20–1.20.1 | 15 |
| 1.20.2 | 18 |
| 1.20.3–1.20.4 | 22 |
| 1.20.5–1.20.6 | 32 |
| 1.21–1.21.1 | 34 |
| 1.21.2–1.21.3 | 42 |
| 1.21.4 | 46 |

> Aggiorna la tabella e `PACK_FORMAT` alla versione target effettiva di
> Starling (protocollo 777 / Java 26.3): il valore sopra è indicativo per le
> versioni note; verifica il pack_format della versione che il motore replica.

## Sconsigliati su mobile

- Pack 64x+ (VRAM eccessiva, vanifica il greedy meshing).
- Pack con CTM/OptiFine-only (richiedono supporto motore dedicato).
- Pack che sostituiscono modelli di block entity con geometrie pesanti
  (il baking statico perde beneficio).