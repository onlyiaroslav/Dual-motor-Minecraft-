# Manifest di download (opzionale)

Il pulsante **Gioca** verifica sempre i file locali. Se vuoi che scarichi
automaticamente runtime Java e file di Minecraft, fornisci un manifest in uno
di questi due modi:

1. **Asset**: metti il file in `starling/src/main/assets/game/manifest.json`
   (incluso nell'APK).
2. **URL remoto**: incolla l'URL in *Impostazioni → File di gioco → Manifest
   download* (preferenza `mc_manifest_url`).

## Formato

```json
{
  "version": "26.3",
  "files": [
    {
      "path": "runtime/libjvm.so",
      "url": "https://example.com/runtime/libjvm.so",
      "size": 48234496,
      "sha1": "…"
    },
    {
      "path": "libraries/foo.jar",
      "url": "https://example.com/libraries/foo.jar",
      "size": 123456
    }
  ]
}
```

- `path` è relativo a `Android/data/dev.starling.app/files/minecraft/`.
- `size` serve per la percentuale e per capire se un file è già completo
  (`0` = sempre riscarica).
- `sha1` è opzionale: se presente il file viene verificato e, in caso di
  mismatch, eliminato e l'installazione fallisce con un messaggio chiaro.
- Il runtime scaricato in `runtime/libjvm.so` viene usato solo se quello
  impacchettato in `jniLibs/arm64-v8a/` non esiste.

Senza manifest l'installer mostra comunque le fasi reali (verifica runtime,
bridge, librerie, asset, versione) e prosegue verso l'avvio.
