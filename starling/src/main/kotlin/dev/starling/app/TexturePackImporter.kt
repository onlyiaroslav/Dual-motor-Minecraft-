package dev.starling.app

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.starling.packs.PackGate
import dev.starling.packs.PackImporter
import dev.starling.packs.Result as PackImportResult
import java.io.File

/**
 * Texture pack importer for Starling (Android/ART layer).
 *
 * The user picks a .zip from the system document picker; the Java core
 * [PackImporter] lo estrae nella sandbox privata (getExternalFilesDir),
 * genera/corregge pack.mcmeta e il launcher lo riconosce subito.
 *
 * FIX INFINITE LOOP: an invalid external pack is rejected with a message
 * ([PackGate.onExternalPackInvalid] → BaseAction.NONE). The download of base
 * files starts ONLY from [maybeDownloadBaseFiles], that is from the real
 * missing-base-files state, never from an external pack failure.
 *
 * Instantiate in onCreate (registerForActivityResult must be called before STARTED).
 * The Java core must be packaged in the APK (dex, runs on ART).
 */
class TexturePackImporter(private val activity: ComponentActivity) {

    companion object {
        // Adatta alla versione Minecraft target di Starling.
        const val PACK_FORMAT = 34
    }

    private val core = PackImporter()
    private val gate = PackGate()
    private val packsDir =
        File(activity.getExternalFilesDir(null), "resourcepacks").apply { mkdirs() }

    private val picker =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val result = import(uri)
                if (result.ok) {
                    toast("Pack imported: ${result.packDir.fileName}")
                    // Notify the launcher/resource manager for the immediate reload.
                    onPackImported(result.packDir.toFile())
                } else {
                    // Pack rejected: error message, NO download restart.
                    toast("Pack rejected: ${result.error}")
                }
            }
        }

    /** Opens the system document picker (ACTION_OPEN_DOCUMENT) for zips. */
    fun launchPicker() {
        picker.launch(arrayOf("application/zip"))
    }

    /** Importa lo zip selezionato nella sandbox privata. */
    fun import(uri: Uri): PackImportResult {
        val name = queryDisplayName(uri) ?: "pack"
        val result = try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                core.importZip(input, packsDir.toPath(), name, PACK_FORMAT)
            } ?: PackImportResult.fail("Unable to open the selected file")
        } catch (e: Exception) {
            PackImportResult.fail("Read failed: ${e.message}")
        }
        if (result.ok) {
            gate.onExternalPackValid()
        } else {
            gate.onExternalPackInvalid(result.error) // → BaseAction.NONE, never download
        }
        return result
    }

    /**
     * Called by the launcher at startup: downloads base files ONLY if missing.
     * External pack failures do not enter this decision.
     */
    fun maybeDownloadBaseFiles(baseFilesMissing: Boolean): PackGate.BaseAction =
        gate.checkBaseFiles(baseFilesMissing)

    /** Hook for the Starling resource manager (immediate pack reload). */
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
// Usage example in a Starling Settings activity:
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