package dev.starling.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Sfondo comune: nero opaco con alone ambra in alto e vignettatura in basso.
// ---------------------------------------------------------------------------
@Composable
fun DogeScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF1A1A1E),
                        DogeBackground,
                        Color(0xFF0E0E10),
                    )
                )
            )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to DogeAccent.copy(alpha = 0.14f),
                            0.55f to DogePrimary.copy(alpha = 0.04f),
                            1f to Color.Transparent,
                        )
                    )
                )
        )
        content()
    }
}

// ---------------------------------------------------------------------------
// Card semitrasparente con bordo sottile (eventualmente luminoso).
// ---------------------------------------------------------------------------
@Composable
fun DogeCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xF226262C), Color(0xF21C1C20))
                )
            )
            .border(
                width = 1.dp,
                color = if (accent) DogePrimary.copy(alpha = 0.38f) else DogeOutline,
                shape = shape,
            )
            .padding(padding),
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Piccola etichetta di sezione con barretta ambra.
// ---------------------------------------------------------------------------
@Composable
fun DogeSectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(DogePrimary)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = DogeTextSecondary,
        )
    }
}

// ---------------------------------------------------------------------------
// Bottoni
// ---------------------------------------------------------------------------
@Composable
fun DogePrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DogePrimary,
            contentColor = Color(0xFF1A1608),
            disabledContainerColor = DogePrimary.copy(alpha = 0.30f),
            disabledContentColor = Color(0xFF1A1608),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun DogeGhostButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DogePrimary.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = DogePrimary),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge)
    }
}

// ---------------------------------------------------------------------------
// Pill di stato (online/offline/versione)
// ---------------------------------------------------------------------------
@Composable
fun DogePill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DogePrimary,
    dot: Boolean = true,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// ---------------------------------------------------------------------------
// Dialogo dark riutilizzabile (sostituisce AlertDialog bianco di default)
// ---------------------------------------------------------------------------
@Composable
fun DogeDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissText: String = "Chiudi",
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(DogeSurfaceHigh)
                .border(1.dp, DogePrimary.copy(alpha = 0.28f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = DogeTextPrimary,
            )
            Spacer(Modifier.height(14.dp))
            content()
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(dismissText, color = DogeTextSecondary)
                }
                if (confirmText != null && onConfirm != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onConfirm,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DogePrimary,
                            contentColor = Color(0xFF1A1608),
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    ) {
                        Text(confirmText.uppercase(), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Campo di testo dark
// ---------------------------------------------------------------------------
@Composable
fun DogeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        placeholder = { Text(placeholder, color = DogeTextSecondary.copy(alpha = 0.6f)) },
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = DogeTextPrimary),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DogePrimary,
            unfocusedBorderColor = DogeOutline,
            disabledBorderColor = DogeOutline,
            errorBorderColor = DogeDanger,
            cursorColor = DogePrimary,
            focusedTextColor = DogeTextPrimary,
            unfocusedTextColor = DogeTextPrimary,
            focusedLabelColor = DogePrimary,
            unfocusedLabelColor = DogeTextSecondary,
            focusedContainerColor = Color(0x14FFFFFF),
            unfocusedContainerColor = Color(0x0AFFFFFF),
        ),
    )
}

// ---------------------------------------------------------------------------
// Avatar giocatore: testa skin da mc-heads.net, fallback icona.
// ---------------------------------------------------------------------------
private object SkinCache {
    private val cache = HashMap<String, ImageBitmap?>()
    fun get(key: String): ImageBitmap? = cache[key]
    fun put(key: String, value: ImageBitmap?) {
        cache[key] = value
    }
    fun contains(key: String): Boolean = cache.containsKey(key)
}

@Composable
fun rememberSkinHead(nickname: String): State<ImageBitmap?> {
    return produceState<ImageBitmap?>(initialValue = SkinCache.get(nickname), key1 = nickname) {
        if (nickname.isBlank()) {
            value = null
            return@produceState
        }
        if (SkinCache.contains(nickname)) {
            value = SkinCache.get(nickname)
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val encoded = URLEncoder.encode(nickname, "UTF-8")
                val conn = URL("https://mc-heads.net/avatar/$encoded/128")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull().also { SkinCache.put(nickname, it) }
        }
    }
}

@Composable
fun DogeAvatar(
    nickname: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    onClick: (() -> Unit)? = null,
) {
    val head by rememberSkinHead(nickname)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(DogeSurfaceHigh)
            .border(2.dp, DogePrimary.copy(alpha = 0.55f), CircleShape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = head
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Skin di $nickname",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.Face,
                contentDescription = null,
                tint = DogePrimary,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Riga chiave/valore usata nelle card informative
// ---------------------------------------------------------------------------
@Composable
fun DogeInfoRow(label: String, value: String, valueColor: Color = DogeTextPrimary) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DogeTextSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
        )
    }
}

/** Formatta i byte in MB con un decimale (es. "124.5 MB"). */
fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100) "${mb.toInt()} MB" else String.format(java.util.Locale.US, "%.1f MB", mb)
}
