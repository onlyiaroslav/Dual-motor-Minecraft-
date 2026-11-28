# Social offline — lista amici e join con codice monouso (Fase 6)

Layer **puramente additivo** per gli account locali di Starling. Non tocca:

- l'autenticazione Microsoft standard (i client `LOGIN_MS` passano intatti);
- il bridge a 8 bit (`bridge-u8` / `bridge-jni`) e la sua latenza;
- `starling-features`, il game loop, la redstone, le farm o le collisioni.

Il modulo vive in `crates/starling-social` ed è esposto alla UI Android da
`java/social-bridge` + `starling/FriendsScreen.kt`.

## Moduli Rust

| Modulo | Contenuto |
|---|---|
| `friends` | `Friend { nickname, uuid, online }`, `FriendList` persistente, UUID v4 locale con OsRng, validazione nickname 3–16 `[A-Za-z0-9_]`. |
| `token` | `TokenStore`: codici di 8 caratteri, TTL 5 minuti, `HashMap` dei token attivi con `SocketAddr` del mondo host, consumo monouso. |
| `network_auth` | `AuthConfig`, `InjectedProfile`, `redeem_code`, `OfflineAuthServer` (TCP, porta dedicata). |
| `tunnel` | `P2pTunnel` (trait), `LanTunnel` (TCP diretto), `FutureP2pTunnel` (stub P2P). |
| `jni` | Export JNI dietro feature `jni`; produce `libstarling_social.so`. |

### UUID locale

`local_uuid()` genera un UUID v4 con `rand::rngs::OsRng` e imposta versione e
variante RFC 4122. Lo stesso nickname mantiene lo stesso UUID nella
`FriendList`: è l'identità stabile usata dal profilo iniettato.

### Token monouso

- Alfabeto `ABCDEFGHJKLMNPQRSTUVWXYZ23456789` (32 simboli, niente `0/O/1/I`).
- 8 caratteri = 40 bit di entropia; collisione rigenerata.
- TTL default 300 s (`DEFAULT_TTL`).
- `consume_checked` rimuove la entry **prima** di controllare la scadenza:
  un codice scaduto non è riprovabile. `consume` è il wrapper `Option`.
- I token non vengono persistiti: al riavvio del launcher decadono tutti.
- I token non compaiono mai nei log.

## Protocollo auth

Il mini-server ascolta su una **porta dedicata** (default suggerito 25566),
separata dalla porta del mondo e dal bridge a 8 bit. Protocollo line-based
UTF-8, una richiesta per riga:

| Client | Server | Significato |
|---|---|---|
| `PING` | `PONG` | Liveness. |
| `LOGIN <nick>` | `CODE_REQUIRED` | Account offline: serve il codice. |
| `TOKEN <code>` | `WELCOME <uuid> <nick> offline` | Codice valido: profilo iniettato, `online_mode=false`. |
| `TOKEN <code>` | `DENY invalid_code` | Codice inesistente o già usato. |
| `TOKEN <code>` | `DENY expired_code` | Oltre il TTL (token rimosso). |
| `TOKEN <code>` | `DENY too_many_attempts` | Superati `max_attempts` (default 3). |
| `LOGIN_MS <nick>` | `PASS_THROUGH microsoft` | Autenticazione standard: questo layer non la tocca. |
| altro | `DENY unknown_command` | Comando non riconosciuto. |

Esempio completo:

```text
client: LOGIN Steve_01
server: CODE_REQUIRED
client: TOKEN ABCD2345
server: WELCOME 5f0c...-... Steve_01 offline
```

### Sicurezza e robustezza

- `AuthConfig::max_attempts` limita i tentativi per connessione.
- `AuthConfig::read_timeout` chiude le connessioni appese.
- Un nickname invalido viene rifiutato **prima** del consumo del token: non
  brucia il codice.
- Dopo un join riuscito il token è già rimosso; un secondo client riceve
  `DENY invalid_code`.
- `InjectedProfile::apply_to_session` forza `online_mode=false` nella sola
  sessione di quel giocatore: la configurazione globale e gli altri client
  Microsoft restano intatti.
- Le connessioni sono gestite su thread separati; un client lento non blocca
  gli altri.
- `stop()`/`Drop` fermano il loop di accept; le connessioni attive scadono al
  timeout.
- L'autenticazione Microsoft non è modificata: `LOGIN_MS` è un pass-through.

### Nota sull'intercettazione del login Minecraft

In questa fase il server auth è un **sidecar** con protocollo dedicato: il
launcher/bridge lo interroga prima di avviare il mondo. Il parser byte-level
del pacchetto Login 777 e l'iniezione nel protocollo di gioco sono il passo
successivo; l'API (`redeem_code`, `InjectedProfile`) è già la seam corretta e
non richiede modifiche al bridge a 8 bit.

## JNI e UI Android

`SocialBridge.java` (pacchetto `dev.starling.social`) espone:

| Metodo | Funzione |
|---|---|
| `SocialBridge(String friendsFile)` | Apre/crea la lista persistente. |
| `addFriend(nick)` | Aggiunge e ritorna l'UUID locale. |
| `listFriends()` | Array di `nickname\|uuid\|online` (`online` = `1`/`0`). |
| `removeFriend(nick)` | Rimuove; `true` se esisteva. |
| `generateInvite(host, port)` | Genera il codice monouso (TTL 5 min). |
| `startAuthServer(port)` | Avvia il server; ritorna la porta effettiva (0 = effimera). |
| `authPort()` / `stopAuthServer()` | Stato e stop del server. |
| `tokenTtlSeconds()` | 300. |
| `close()` | Libera l'handle e ferma il server. |

Gli handle sono ID opachi in una registry globale: nessun puntatore raw
attraversa JNI. Un panic Rust viene catturato al confine e convertito in
`IllegalStateException`.

`starling/FriendsScreen.kt` (`FriendsActivity`) mostra lista, campo nickname,
pulsante "Invita nel mondo", countdown e copia negli appunti; avvia il server
auth in `onCreate` e lo ferma in `onDestroy`. Tiene premuto un amico per
rimuoverlo.

### Packaging APK

```bash
cargo ndk -t arm64-v8a --platform 33 \
  -o starling/app/src/main/jniLibs \
  build --release --package starling-social --features jni
```

La `.so` risultante è `libstarling_social.so` e va inclusa in
`jniLibs/arm64-v8a/`. La classe `dev.starling.social.SocialBridge` deve essere
nel dex dell'APK (sorgente in `java/social-bridge`). Il nome caricato da
`System.loadLibrary("starling_social")` deve coincidere.

## Networking: LAN e P2P futuro

- **LAN** (`LanTunnel`): TCP diretto verso `host:porta`, con timeout e
  `TCP_NODELAY`. Funziona sulla stessa rete Wi-Fi.
- **P2P** (`FutureP2pTunnel`): stub che risponde `Unsupported`; il trait
  `P2pTunnel` è la seam per iroh/libp2p (relay/hole-punching) per NAT/CGNAT.
  Non è implementato in questa fase, per scelta esplicita.

Il tunnel è solo trasporto: non entra nel percorso del bridge a 8 bit.

## Test

Rust (`cargo test -p starling-social`):

- token: formato, unicità su 1000 generazioni, monouso, scadenza, purge;
- friends: UUID v4, validazione, duplicati, persistenza round-trip,
  `ensure_online`;
- network_auth: redeem valido, scaduto non riprovabile, nickname invalido che
  non brucia il token, UUID stabile, server end-to-end, max attempts, ping,
  stop, Microsoft pass-through;
- tunnel: LAN reale su porta locale, peer invalido, stub P2P.

Integrazione (`tests/offline_join.rs`): due client reali (host con token +
offline), riuso negato, scadenza, Microsoft, LAN.

Convivenza (`tests/coexistence.rs`): join offline a mondo già costruito,
20 tick identici a un mondo di controllo, pistone che spinge una cassa baked
con invalidazione same-frame, X-Ray che nasconde la pietra ma non tocca la
collisione, casse statiche renderizzate con X-Ray spento, farm identica.

Java/JNI (`bash java/social-bridge/build.sh`): 20 test su TTL, lista
persistente, invito, server, riuso negato, chiusura.

## Non regressione

La Fase 6 non modifica file della Fase 5. L'unica modifica a file esistenti è
l'aggiunta del crate al workspace (`Cargo.toml`) e le sezioni di
documentazione. `starling-features`, il game loop e `bridge-jni` restano
intatti; la suite completa del workspace continua a passare.
