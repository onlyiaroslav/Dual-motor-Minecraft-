package dev.starling.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON minimale (zero dipendenze) per i manifest delle mod
 * ({@code fabric.mod.json}, {@code mcmod.info}). Supporta oggetti, array,
 * stringhe con escape, numeri, booleani e null. Non è un parser generale:
 * è volutamente piccolo e auditabile.
 */
public final class MiniJson {
    private final String src;
    private int pos;

    private MiniJson(String src) {
        this.src = src;
    }

    /** Parse in Map/List/String/Double/Boolean/null. */
    public static Object parse(String text) throws BridgeException {
        MiniJson p = new MiniJson(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) {
            throw p.err("contenuto extra dopo il valore JSON");
        }
        return v;
    }

    private BridgeException err(String msg) {
        return new BridgeException("JSON non valido a offset " + pos + ": " + msg);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() throws BridgeException {
        if (pos >= src.length()) {
            throw err("fine input inattesa");
        }
        return src.charAt(pos);
    }

    private Object value() throws BridgeException {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String word) throws BridgeException {
        if (!src.startsWith(word, pos)) {
            throw err("atteso '" + word + "'");
        }
        pos += word.length();
    }

    private Map<String, Object> object() throws BridgeException {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // {
        skipWs();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWs();
            String key = string();
            skipWs();
            if (peek() != ':') {
                throw err("atteso ':'");
            }
            pos++;
            skipWs();
            map.put(key, value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                return map;
            } else {
                throw err("atteso ',' o '}'");
            }
        }
    }

    private List<Object> array() throws BridgeException {
        List<Object> list = new ArrayList<>();
        pos++; // [
        skipWs();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(value());
            skipWs();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                return list;
            } else {
                throw err("atteso ',' o ']'");
            }
        }
    }

    private String string() throws BridgeException {
        if (peek() != '"') {
            throw err("attesa stringa");
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            pos++;
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = peek();
                pos++;
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > src.length()) {
                            throw err("escape \\u incompleto");
                        }
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: throw err("escape sconosciuto: \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Double number() throws BridgeException {
        int start = pos;
        while (pos < src.length() && "+-0123456789.eE".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        if (start == pos) {
            throw err("valore non riconosciuto");
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            throw err("numero non valido: " + src.substring(start, pos));
        }
    }
}