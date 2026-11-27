#!/usr/bin/env python3
"""
Verifica statica dei sorgenti Kotlin di starling/ (nessuna dipendenza esterna).

Intercetta in pochi secondi le classi di errore che hanno rotto la CI:
  1. delegate `by remember/rememberSaveable/produceState/...` senza gli import
     androidx.compose.runtime.getValue / setValue;
  2. simboli Compose/Material3 usati ma non importati;
  3. commenti a blocchi nidificati (Kotlin li annida: `(ai/*)` mangia il file);
  4. parentesi/graffe non bilanciate, stringhe o commenti non chiusi;
  5. API Java non disponibili negli stub Android (es. Process.pid());
  6. simboli di progetto (Doge*, *Content, GameInstaller, ...) mai definiti.

Uso:  python3 scripts/check-kotlin.py [radice_repo]
Exit: 0 = tutto ok, 1 = problemi trovati (la CI si ferma subito).
"""

import os
import re
import sys

# Simbolo -> import richiesto (solo quelli che hanno causato errori reali).
REQUIRED_IMPORTS = {
    "getValue": "androidx.compose.runtime.getValue",
    "setValue": "androidx.compose.runtime.setValue",
    "Composable": "androidx.compose.runtime.Composable",
    "remember": "androidx.compose.runtime.remember",
    "rememberSaveable": "androidx.compose.runtime.saveable.rememberSaveable",
    "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "mutableIntStateOf": "androidx.compose.runtime.mutableIntStateOf",
    "produceState": "androidx.compose.runtime.produceState",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect",
    "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope",
    "Modifier": "androidx.compose.ui.Modifier",
    "Alignment": "androidx.compose.ui.Alignment",
    "LocalContext": "androidx.compose.ui.platform.LocalContext",
    "Color": "androidx.compose.ui.graphics.Color",
    "Brush": "androidx.compose.ui.graphics.Brush",
    "ImageBitmap": "androidx.compose.ui.graphics.ImageBitmap",
    "asImageBitmap": "androidx.compose.ui.graphics.asImageBitmap",
    "ContentScale": "androidx.compose.ui.layout.ContentScale",
    "clip": "androidx.compose.ui.draw.clip",
    "dp": "androidx.compose.ui.unit.dp",
    "sp": "androidx.compose.ui.unit.sp",
    "Dp": "androidx.compose.ui.unit.Dp",
    "FontWeight": "androidx.compose.ui.text.font.FontWeight",
    "TextOverflow": "androidx.compose.ui.text.style.TextOverflow",
    "TextAlign": "androidx.compose.ui.text.style.TextAlign",
    "KeyboardType": "androidx.compose.ui.text.input.KeyboardType",
    "KeyboardOptions": "androidx.compose.foundation.text.KeyboardOptions",
    "Dialog": "androidx.compose.ui.window.Dialog",
    "CircleShape": "androidx.compose.foundation.shape.CircleShape",
    "RoundedCornerShape": "androidx.compose.foundation.shape.RoundedCornerShape",
    "background": "androidx.compose.foundation.background",
    "border": "androidx.compose.foundation.border",
    "clickable": "androidx.compose.foundation.clickable",
    "combinedClickable": "androidx.compose.foundation.combinedClickable",
    "verticalScroll": "androidx.compose.foundation.verticalScroll",
    "rememberScrollState": "androidx.compose.foundation.rememberScrollState",
    "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn",
    "Image": "androidx.compose.foundation.Image",
    "Box": "androidx.compose.foundation.layout.Box",
    "Column": "androidx.compose.foundation.layout.Column",
    "Row": "androidx.compose.foundation.layout.Row",
    "Spacer": "androidx.compose.foundation.layout.Spacer",
    "Arrangement": "androidx.compose.foundation.layout.Arrangement",
    "PaddingValues": "androidx.compose.foundation.layout.PaddingValues",
    "fillMaxSize": "androidx.compose.foundation.layout.fillMaxSize",
    "fillMaxWidth": "androidx.compose.foundation.layout.fillMaxWidth",
    "padding": "androidx.compose.foundation.layout.padding",
    "size": "androidx.compose.foundation.layout.size",
    "height": "androidx.compose.foundation.layout.height",
    "width": "androidx.compose.foundation.layout.width",
    "statusBarsPadding": "androidx.compose.foundation.layout.statusBarsPadding",
    "systemBarsPadding": "androidx.compose.foundation.layout.systemBarsPadding",
    "Text": "androidx.compose.material3.Text",
    "Button": "androidx.compose.material3.Button",
    "ButtonDefaults": "androidx.compose.material3.ButtonDefaults",
    "OutlinedButton": "androidx.compose.material3.OutlinedButton",
    "TextButton": "androidx.compose.material3.TextButton",
    "Icon": "androidx.compose.material3.Icon",
    "IconButton": "androidx.compose.material3.IconButton",
    "MaterialTheme": "androidx.compose.material3.MaterialTheme",
    "OutlinedTextField": "androidx.compose.material3.OutlinedTextField",
    "OutlinedTextFieldDefaults": "androidx.compose.material3.OutlinedTextFieldDefaults",
    "Switch": "androidx.compose.material3.Switch",
    "SwitchDefaults": "androidx.compose.material3.SwitchDefaults",
    "NavigationBar": "androidx.compose.material3.NavigationBar",
    "NavigationBarItem": "androidx.compose.material3.NavigationBarItem",
    "NavigationBarItemDefaults": "androidx.compose.material3.NavigationBarItemDefaults",
    "LinearProgressIndicator": "androidx.compose.material3.LinearProgressIndicator",
    "CircularProgressIndicator": "androidx.compose.material3.CircularProgressIndicator",
    "Typography": "androidx.compose.material3.Typography",
    "Shapes": "androidx.compose.material3.Shapes",
    "darkColorScheme": "androidx.compose.material3.darkColorScheme",
    "Icons": "androidx.compose.material.icons.Icons",
    "BackHandler": "androidx.activity.compose.BackHandler",
    "setContent": "androidx.activity.compose.setContent",
    "rememberLauncherForActivityResult": "androidx.activity.compose.rememberLauncherForActivityResult",
    "ActivityResultContracts": "androidx.activity.result.contract.ActivityResultContracts",
    "enableEdgeToEdge": "androidx.activity.enableEdgeToEdge",
    "SystemBarStyle": "androidx.activity.SystemBarStyle",
    "ComponentActivity": "androidx.activity.ComponentActivity",
    "Dispatchers": "kotlinx.coroutines.Dispatchers",
    "withContext": "kotlinx.coroutines.withContext",
    "launch": "kotlinx.coroutines.launch",
    "delay": "kotlinx.coroutines.delay",
    "thread": "kotlin.concurrent.thread",
    "File": "java.io.File",
    "Uri": "android.net.Uri",
    "Intent": "android.content.Intent",
    "Context": "android.content.Context",
    "Bundle": "android.os.Bundle",
    "Toast": "android.widget.Toast",
    "JSONObject": "org.json.JSONObject",
    "HttpURLConnection": "java.net.HttpURLConnection",
    "URL": "java.net.URL",
    "URLEncoder": "java.net.URLEncoder",
    "MessageDigest": "java.security.MessageDigest",
    "BitmapFactory": "android.graphics.BitmapFactory",
    "ClipboardManager": "android.content.ClipboardManager",
    "ClipData": "android.content.ClipData",
    "NetworkInterface": "java.net.NetworkInterface",
    "Inet4Address": "java.net.Inet4Address",
    "Collections": "java.util.Collections",
    "Locale": "java.util.Locale",
}

# API Java/desktop non presenti negli stub Android: errore garantito.
FORBIDDEN_CALLS = [
    (re.compile(r"\.pid\(\)"), "Process.pid(): non esposto negli stub Android"),
    (re.compile(r"\bjava\.lang\.ProcessHandle\b"), "ProcessHandle: non disponibile su Android"),
    (re.compile(r"\bjavax\.swing\b"), "javax.swing: non disponibile su Android"),
    (re.compile(r"\bjava\.awt\b"), "java.awt: non disponibile su Android"),
]

DELEGATE_RE = re.compile(r"\bby\s+(\w+)")
# Delegate che NON richiedono androidx.compose.runtime.getValue
STDLIB_DELEGATES = {"lazy", "observable", "vetoable", "map", "this"}


def strip_code(text):
    """Rimuove commenti e stringhe, mantenendo la lunghezza (per gli indici).

    Il codice dentro le interpolazioni `${...}` viene CONSERVATO: è lì che si
    nascondono errori reali (es. `"pid ${process.pid()}"`).
    """
    out = []
    i, n = 0, len(text)
    state = "code"
    depth = 0
    interp_depth = 0
    issues = []
    while i < n:
        c = text[i]
        if state == "code":
            if text[i:i + 2] == "//":
                state = "line"
                out.append("  ")
                i += 2
                continue
            if text[i:i + 2] == "/*":
                state = "block"
                depth = 1
                out.append("  ")
                i += 2
                continue
            if text[i:i + 3] == '"""':
                state = "raw"
                out.append("   ")
                i += 3
                continue
            if c == '"':
                state = "str"
                out.append(" ")
                i += 1
                continue
            if c == "'":
                state = "char"
                out.append(" ")
                i += 1
                continue
            out.append(c)
            i += 1
        elif state == "interp":
            # codice dentro ${...}: conservato, con conteggio delle graffe
            if c == "{":
                interp_depth += 1
            elif c == "}":
                interp_depth -= 1
                if interp_depth == 0:
                    state = "str"
                    out.append(" ")
                    i += 1
                    continue
            if text[i:i + 2] == "//":
                state = "line"
                out.append("  ")
                i += 2
                continue
            out.append(c)
            i += 1
        elif state == "line":
            if c == "\n":
                state = "code" if interp_depth == 0 else "interp"
                out.append("\n")
            else:
                out.append(" ")
            i += 1
        elif state == "block":
            if text[i:i + 2] == "/*":
                depth += 1
                issues.append("commento a blocchi nidificato (Kotlin li annida)")
                out.append("  ")
                i += 2
                continue
            if text[i:i + 2] == "*/":
                depth -= 1
                out.append("  ")
                i += 2
                if depth == 0:
                    state = "code"
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
        elif state == "str":
            if text[i:i + 2] == "${":
                state = "interp"
                interp_depth = 1
                out.append("  ")
                i += 2
                continue
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if c == '"':
                state = "code"
            out.append(" ")
            i += 1
        elif state == "raw":
            if text[i:i + 3] == '"""':
                state = "code"
                out.append("   ")
                i += 3
                continue
            if text[i:i + 2] == "${":
                state = "interp"
                interp_depth = 1
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
        elif state == "char":
            if c == "\\":
                out.append("  ")
                i += 2
                continue
            if c == "'":
                state = "code"
            out.append(" ")
            i += 1
    if state == "block":
        issues.append("commento a blocchi non chiuso")
    if state == "raw":
        issues.append("raw string non chiusa")
    return "".join(out), issues


def check_balance(code):
    issues = []
    pairs = {"{": "}", "(": ")", "[": "]"}
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in pairs:
            stack.append((ch, line))
        elif ch in pairs.values():
            if not stack:
                issues.append(f"'{ch}' senza apertura (riga {line})")
            else:
                opener, oline = stack.pop()
                if pairs[opener] != ch:
                    issues.append(f"'{opener}' (riga {oline}) chiusa da '{ch}' (riga {line})")
    for opener, oline in stack:
        issues.append(f"'{opener}' non chiusa (riga {oline})")
    return issues


def check_file(path):
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    issues = []

    code, comment_issues = strip_code(text)
    issues.extend(comment_issues)
    issues.extend(check_balance(code))

    imports = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
    imported_names = {imp.rsplit(".", 1)[-1] for imp in imports}
    wildcard_pkgs = {imp.rsplit(".", 1)[0] for imp in imports if imp.endswith(".*")}

    # 1. delegate `by ...` senza getValue/setValue.
    #    Qualsiasi delegate di stato Compose (remember, rememberSaveable,
    #    produceState, animate*AsState, o un helper che ritorna State) richiede
    #    l'operatore getValue importato: è l'errore che ha rotto la CI.
    delegates = [name for name in DELEGATE_RE.findall(code) if name not in STDLIB_DELEGATES]
    uses_compose = "@Composable" in code or any(
        imp.startswith("androidx.compose") for imp in imports
    )
    if delegates and uses_compose:
        if "getValue" not in imported_names and "androidx.compose.runtime" not in wildcard_pkgs:
            issues.append(
                "delegate 'by " + delegates[0] + "' usato ma manca "
                "import androidx.compose.runtime.getValue"
            )
        if re.search(r"\bvar\s+\w+\s+by\s+", code):
            if "setValue" not in imported_names and "androidx.compose.runtime" not in wildcard_pkgs:
                issues.append(
                    "delegate 'var ... by' usato ma manca "
                    "import androidx.compose.runtime.setValue"
                )

    # 2. simboli usati ma non importati.
    #    - tipi/oggetti (Maiuscoli): uso come chiamante, receiver o tipo;
    #    - estensioni (minuscoli): compaiono sempre dopo un '.' (Modifier.clip,
    #      16.dp, scope.launch). Gli argomenti nominati (`background = ...`)
    #      NON sono usi del simbolo e vengono ignorati.
    defined = set(re.findall(
        r"\b(?:fun|val|var|class|object|interface|enum class|data class|sealed interface|typealias)\s+(\w+)",
        code,
    ))
    defined |= set(re.findall(r"\b(?:fun|val|var)\s+(?:\w+\.)?(\w+)\s*[(:=]", code))
    for symbol, required in REQUIRED_IMPORTS.items():
        if symbol in ("getValue", "setValue"):
            continue  # gestiti dal controllo sui delegate
        if symbol in imported_names or symbol in defined:
            continue
        if required.rsplit(".", 1)[0] in wildcard_pkgs:
            continue
        esc = re.escape(symbol)
        if symbol[0].isupper():
            patterns = [
                r"(?<![\w.])" + esc + r"\s*[.(<]",
                r"[:<]\s*" + esc + r"\b",
            ]
        elif symbol in ("dp", "sp"):
            patterns = [r"\.\s*" + esc + r"\b"]
        elif symbol == "launch":
            # scope.launch { ... } = coroutine; picker.launch(...) = ActivityResultLauncher
            patterns = [r"\.\s*" + esc + r"\s*\{"]
        else:
            patterns = [r"\.\s*" + esc + r"\s*[({]"]
        if any(re.search(pattern, code) for pattern in patterns):
            issues.append(f"'{symbol}' usato ma non importato (serve {required})")

    # 3. API vietate
    for regex, message in FORBIDDEN_CALLS:
        if regex.search(code):
            issues.append(message)

    return issues


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    source_dir = os.path.join(root, "starling", "src", "main", "kotlin")
    if not os.path.isdir(source_dir):
        print(f"[check-kotlin] directory non trovata: {source_dir}")
        return 1

    files = []
    for base, _, names in os.walk(source_dir):
        for name in sorted(names):
            if name.endswith(".kt"):
                files.append(os.path.join(base, name))

    total = 0
    for path in sorted(files):
        issues = check_file(path)
        rel = os.path.relpath(path, root)
        if issues:
            total += len(issues)
            print(f"[FAIL] {rel}")
            for issue in issues:
                print(f"       - {issue}")
        else:
            print(f"[ ok ] {rel}")

    print(f"\n[check-kotlin] {len(files)} file, {total} problemi")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
