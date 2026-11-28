package dev.starling.app

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.starling.packs.BaseAction
import dev.starling.packs.PackGate
import dev.starling.packs.PackImporter
import dev.starling.packs.Result as PackImportResult
import java.io.File

/**
 * Importatore texture pack per Starling (layer Android/ART).
 *
 * Selezione file con ACTION_OPEN_DOCUMENT (ActivityResultContracts.OpenDocument):
 * l'utente sceglie uno .zip dal document picker di sistema; il core Java
 * [PackImporter] lo estrae nella sandbox privata (getExternalFilesDir),
 * genera/corregge pack.mcmeta e il launcher lo riconosce subito.
 *
 * FIX LOOP INFINITO: un pack esterno non valido viene scartato con un
 * messaggio ([PackGate.onExternalPackInvalid] → BaseAction.NONE). Il download
 * dei file base parte SOLO da [maybeDownloadBaseFiles], cioè dalla mancanza
 * reale dei file base, mai dal fallimento di un pack esterno.
 *
 * Istanziare in onCreate (registerForActivityResult va chiamato prima di STARTED).
 * Il core Java va impacchettato nell'APK (dex, gira su ART).
 */
class TexturePackImporter(private val activity: ComponentActivity) {

    companion object {
        // Adatta alla versione Minecraft target di Starling.
        const val PACK_FORMAT = 34
    }

    private val core = PackImporter()
    private val gate = PackGate()
    private val packsDir =
        File(activity.getExternalFilesDir(null) ?: activity.filesDir, "resourcepacks")
            .apply { mkdirs() }

    private val picker =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val result = import(uri)
                if (result.ok) {
                    val dir = result.packDir
                    if (dir != null) {
                        toast("Pack importato: ${dir.fileName}")
                        // Notifica il launcher/resource manager per il reload immediato.
                        onPackImported(dir.toFile())
                    }
                } else {
                    // Pack scartato: messaggio d'errore, NESSUN restart dei download.
                    toast("Pack scartato: ${result.error}")
                }
            }
        }

    /** Apre il document picker di sistema (ACTION_OPEN_DOCUMENT) sugli zip. */
    fun launchPicker() {
        picker.launch(arrayOf("application/zip"))
    }

    /** Importa lo zip selezionato nella sandbox privata. */
    fun import(uri: Uri): PackImportResult {
        val name = queryDisplayName(uri) ?: "pack"
        val result = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                core.importZip(input, packsDir.toPath(), name, PACK_FORMAT)
            } ?: PackImportResult.fail("impossibile aprire il file selezionato")
        } catch (e: Exception) {
            PackImportResult.fail("lettura fallita: ${e.message}")
        }
        if (result.ok) {
            gate.onExternalPackValid()
        } else {
            gate.onExternalPackInvalid(result.error ?: "errore sconosciuto") // → BaseAction.NONE, mai download
        }
        return result
    }

    /**
     * Chiamata dal launcher all'avvio: scarica i file base SOLO se mancano
     * davvero. Il fallimento di pack esterni non entra in questa decisione.
     */
    fun maybeDownloadBaseFiles(baseFilesMissing: Boolean): BaseAction =
        gate.checkBaseFiles(baseFilesMissing)

    /** Hook per il resource manager di Starling (reload immediato del pack). */
    var onPackImported: (File) -> Unit = {}

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
    }
}

// ---------------------------------------------------------------------------
// Esempio d'uso in un'Activity delle impostazioni di Starling:
//
// class SettingsActivity : ComponentActivity() {
//     private lateinit var importer: TexturePackImporter
//     override fun onCreate(savedInstanceState: Bundle?) {
//         super.onCreate(savedInstanceState)
//         importer = TexturePackImporter(this)
//         importer.onPackImported = { dir -> starlingResourceManager.reloadPacks(dir) }
//         findViewById<Button>(R.id.import_pack).setOnClickListener {
//             importer.launchPicker()   // ACTION_OPEN_DOCUMENT → .zip
//         }
//     }
// }
// ---------------------------------------------------------------------------