//! Parsing JAR (ZIP) minimale: central directory + local header + firme
//! dei metodi nelle classi `.class`. Estrae **solo** firme: i flussi di dati
//! vengono letti per offset e non conservati (niente bytecode, niente
//! decompilazione, vincolo legale del tool di conversione).

use crate::{AnalyzerError, JavaMethodSignature};

const LOCAL_SIG: u32 = 0x0403_4b50;
const CENTRAL_SIG: u32 = 0x0201_4b50;
const EOCD_SIG: u32 = 0x0605_4b50;

fn le_u16(d: &[u8], at: usize) -> u16 {
    u16::from_le_bytes([d[at], d[at + 1]])
}

fn le_u32(d: &[u8], at: usize) -> u32 {
    u32::from_le_bytes([d[at], d[at + 1], d[at + 2], d[at + 3]])
}

fn be_u16(d: &[u8], at: usize) -> u16 {
    u16::from_be_bytes([d[at], d[at + 1]])
}

fn be_u32(d: &[u8], at: usize) -> u32 {
    u32::from_be_bytes([d[at], d[at + 1], d[at + 2], d[at + 3]])
}

fn need(d: &[u8], at: usize, n: usize) -> Result<(), String> {
    if at + n > d.len() {
        return Err(format!("EOF fuori range: richiesto fino a {} ma disponibili {}", at + n, d.len()));
    }
    Ok(())
}

struct JarEntry {
    name: String,
    data: Vec<u8>,
}

pub struct JarFile<'a> {
    data: &'a [u8],
}

impl<'a> JarFile<'a> {
    pub fn new(jar_bytes: &'a [u8]) -> Result<Self, AnalyzerError> {
        Ok(Self { data: jar_bytes })
    }

    fn entries(&self) -> Result<Vec<JarEntry>, AnalyzerError> {
        let mut eocd: Option<usize> = None;
        let mut end = self.data.len();
        while end >= 4 {
            if le_u32(self.data, end - 4) == EOCD_SIG {
                eocd = Some(end - 4);
                break;
            }
            end -= 1;
        }
        let eocd =
            eocd.ok_or_else(|| AnalyzerError::Malformed("End of Central Directory non trovato".into()))?;
        need(&self.data, eocd, 22)?;
        let entries_total = le_u16(self.data, eocd + 10) as usize;
        let cd_size = le_u32(self.data, eocd + 12) as usize;
        let cd_offset = le_u32(self.data, eocd + 16) as usize;
        let cd_end = cd_offset
            .checked_add(cd_size)
            .ok_or_else(|| AnalyzerError::Malformed("cd overflow".into()))?;
        if cd_end > self.data.len() {
            return Err(AnalyzerError::Malformed("central directory fuori dal buffer".into()));
        }

        let mut out = Vec::new();
        let mut off = cd_offset;
        for _ in 0..entries_total {
            need(&self.data, off, 46)?;
            if le_u32(self.data, off) != CENTRAL_SIG {
                return Err(AnalyzerError::Malformed(
                    "firma central directory non valida".into(),
                ));
            }
            let method = le_u16(self.data, off + 10) as u32;
            let csize = le_u32(self.data, off + 20) as usize;
            let name_len = le_u16(self.data, off + 28) as usize;
            let extra_len = le_u16(self.data, off + 30) as usize;
            let comment_len = le_u16(self.data, off + 32) as usize;
            let local_offset = le_u32(self.data, off + 42) as usize;
            need(&self.data, off + 46, name_len)?;
            let name = String::from_utf8_lossy(&self.data[off + 46..off + 46 + name_len]).to_string();
            need(&self.data, local_offset, 30)?;
            if le_u32(self.data, local_offset) != LOCAL_SIG {
                return Err(AnalyzerError::Malformed("firma local header non valida".into()));
            }
            let data_start = local_offset + 30 + le_u16(self.data, local_offset + 26) as usize
                + le_u16(self.data, local_offset + 28) as usize;
            let data_end = data_start + csize;
            need(&self.data, data_start, csize)?;
            let data = if method == 8 {
                use std::io::Read;
                let mut decoder = flate2::read::DeflateDecoder::new(std::io::Cursor::new(
                    &self.data[data_start..data_end],
                ));
                let mut buf = Vec::new();
                decoder
                    .read_to_end(&mut buf)
                    .map_err(|e| AnalyzerError::Malformed(format!("deflate fallito: {e}")))?;
                buf
            } else {
                self.data[data_start..data_end].to_vec()
            };
            out.push(JarEntry { name, data });
            off += 46 + name_len + extra_len + comment_len;
        }
        Ok(out)
    }
}

/// Estrae le firme (class_name, name, descriptor) di tutte le classi `.class`.
pub fn extract_method_signatures(
    jar_bytes: &[u8],
) -> Result<Vec<JavaMethodSignature>, AnalyzerError> {
    let jar = JarFile::new(jar_bytes)?;
    let entries = jar.entries()?;
    let mut out = Vec::new();
    for entry in entries {
        let lower = entry.name.to_ascii_lowercase();
        if !lower.ends_with(".class") {
            continue;
        }
        let stem = entry.name.trim_end_matches(".class");
        let class_name = stem.replace('/', ".");
        for sig in parse_class(&class_name, &entry.data)? {
            out.push(sig);
        }
    }
    Ok(out)
}

/// Estrae le firme dei metodi da un array di bytecode di classe.
pub fn parse_class(
    class_name: &str,
    bytes: &[u8],
) -> Result<Vec<JavaMethodSignature>, AnalyzerError> {
    let err = |m: String| AnalyzerError::Malformed(m);
    if bytes.len() < 10 || be_u32(bytes, 0) != 0xCAFE_BABE {
        return Err(err(format!("magic class non valido per {class_name}")));
    }
    let cp_count = be_u16(bytes, 8) as usize;
    let mut utf8s: Vec<String> = vec![String::new(); cp_count];
    let mut pos = 10usize;
    for i in 1..cp_count {
        let tag = be_u16(bytes, pos) as u16;
        pos += 2;
        match tag {
            1 => {
                let len = be_u16(bytes, pos) as usize;
                pos += 2;
                utf8s[i] = String::from_utf8_lossy(&bytes[pos..pos + len]).into_owned();
                pos += len;
            }
            7 | 8 | 16 | 19 | 20 => {
                pos += 2;
            }
            15 => {
                pos += 3;
            }
            3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => {
                pos += 4;
            }
            _ => return Err(err(format!("JVM costruttore con tag {tag}"))),
        }
    }

    let _access_flags = be_u16(bytes, pos);
    let _this_class = be_u16(bytes, pos + 2);
    let _super_class = be_u16(bytes, pos + 4);
    let interfaces_count = be_u16(bytes, pos + 6) as usize;
    let mut p = pos + 8 + interfaces_count * 2;
    let fields_count = be_u16(bytes, p) as usize;
    p += 2;
    for _ in 0..fields_count {
        p += 6;
        let attr_count = be_u16(bytes, p) as usize;
        p += 2;
        for _ in 0..attr_count {
            let alen = be_u32(bytes, p + 2) as usize;
            p += 6 + alen;
        }
    }
    let methods_count = be_u16(bytes, p) as usize;
    let mut methods = Vec::new();
    let mut q = p + 2;
    for _ in 0..methods_count {
        let name_idx = be_u16(bytes, q + 2) as usize;
        let desc_idx = be_u16(bytes, q + 4) as usize;
        let attr_count = be_u16(bytes, q + 6) as usize;
        let mut r = q + 8;
        for _ in 0..attr_count {
            let alen = be_u32(bytes, r + 2) as usize;
            r += 6 + alen;
        }
        methods.push((name_idx, desc_idx));
        q = r;
    }

    let class_name_norm = class_name.replace('/', ".");
    Ok(methods
        .into_iter()
        .map(|(name_idx, desc_idx)| JavaMethodSignature {
            class_name: class_name_norm.clone(),
            name: utf8s.get(name_idx).cloned().unwrap_or_default(),
            descriptor: utf8s.get(desc_idx).cloned().unwrap_or_default(),
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_u16(b: &mut Vec<u8>, v: u16) {
        b.extend_from_slice(&v.to_le_bytes());
    }
    fn put_u32(b: &mut Vec<u8>, v: u32) {
        b.extend_from_slice(&v.to_le_bytes());
    }
    fn put_utf8(b: &mut Vec<u8>, s: &str) {
        let bytes = s.as_bytes();
        b.extend_from_slice(&1u16.to_be_bytes()); // CONSTANT_Utf8 tag (big-endian)
        b.extend_from_slice(&(bytes.len() as u16).to_be_bytes()); // length (big-endian)
        b.extend_from_slice(bytes);
    }
    fn put_be_u16(b: &mut Vec<u8>, v: u16) {
        b.extend_from_slice(&v.to_be_bytes());
    }

    fn build_jar(entries: &[(&str, &[u8])]) -> Vec<u8> {
        let mut out = Vec::new();
        let mut offsets: Vec<(String, usize, usize)> = Vec::new();
        for (name, data) in entries {
            let nb = name.as_bytes().to_vec();
            let lh_off = out.len();
            put_u32(&mut out, 0x04034B50); // signature
            put_u16(&mut out, 20); // version needed
            put_u16(&mut out, 0); // flags
            put_u16(&mut out, 0); // method (stored)
            put_u16(&mut out, 0); // mod time
            put_u16(&mut out, 0); // mod date
            put_u32(&mut out, 0); // crc32
            put_u32(&mut out, data.len() as u32); // compressed size
            put_u32(&mut out, data.len() as u32); // uncompressed size
            put_u16(&mut out, nb.len() as u16); // name length
            put_u16(&mut out, 0); // extra length
            out.extend_from_slice(&nb); // name
            out.extend_from_slice(data); // data
            offsets.push((name.to_string(), lh_off, data.len()));
        }
        let cd_off = out.len();
        for (name, lh_off, len) in &offsets {
            let nb = name.as_bytes();
            put_u32(&mut out, 0x02014B50); // signature
            put_u16(&mut out, 20); // version made by
            put_u16(&mut out, 20); // version needed
            put_u16(&mut out, 0); // flags
            put_u16(&mut out, 0); // method (stored)
            put_u16(&mut out, 0); // mod time
            put_u16(&mut out, 0); // mod date
            put_u32(&mut out, 0); // crc32
            put_u32(&mut out, *len as u32); // compressed size
            put_u32(&mut out, *len as u32); // uncompressed size
            put_u16(&mut out, nb.len() as u16); // name length
            put_u16(&mut out, 0); // extra length
            put_u16(&mut out, 0); // comment length
            put_u16(&mut out, 0); // disk number start
            put_u16(&mut out, 0); // internal attrs
            put_u32(&mut out, 0); // external attrs
            put_u32(&mut out, *lh_off as u32); // local header offset
            out.extend_from_slice(nb); // name
        }
        let cd_size = out.len() - cd_off;
        put_u32(&mut out, 0x06054B50);
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u16(&mut out, offsets.len() as u16);
        put_u16(&mut out, offsets.len() as u16);
        put_u32(&mut out, cd_size as u32);
        put_u32(&mut out, cd_off as u32);
        put_u16(&mut out, 0);
        out
    }

    fn class_bytes() -> Vec<u8> {
        let mut b = Vec::new();
        b.extend_from_slice(&[0xCA, 0xFE, 0xBA, 0xBE]);
        put_be_u16(&mut b, 0);
        put_be_u16(&mut b, 52);
        put_be_u16(&mut b, 6);
        put_utf8(&mut b, "Test");
        put_utf8(&mut b, "a");
        put_utf8(&mut b, "()V");
        put_utf8(&mut b, "b");
        put_utf8(&mut b, "(Ljava/lang/String;)Z");
        put_be_u16(&mut b, 9);
        put_be_u16(&mut b, 1);
        put_be_u16(&mut b, 0);
        put_be_u16(&mut b, 0);
        put_be_u16(&mut b, 0);
        put_be_u16(&mut b, 2);
        put_be_u16(&mut b, 9);
        put_be_u16(&mut b, 2);
        put_be_u16(&mut b, 3);
        put_be_u16(&mut b, 0);
        put_be_u16(&mut b, 9);
        put_be_u16(&mut b, 4);
        put_be_u16(&mut b, 5);
        put_be_u16(&mut b, 0);
        b
    }

    #[test]
    fn estratt_e_le_firme_dei_metodi() {
        let jar = build_jar(&[("com/example/Test.class", &class_bytes())]);
        let sigs = extract_method_signatures(&jar).unwrap();
        assert_eq!(sigs.len(), 2);
        assert_eq!(sigs[0].class_name, "com.example.Test");
        assert_eq!(sigs[0].name, "a");
        assert_eq!(sigs[0].descriptor, "()V");
        assert_eq!(sigs[1].class_name, "com.example.Test");
        assert_eq!(sigs[1].name, "b");
        assert_eq!(sigs[1].descriptor, "(Ljava/lang/String;)Z");
    }

    #[test]
    fn jar_senza_classi_restituisce_vuoto() {
        let jar = build_jar(&[("com/example/README.txt", b"hi")]);
        assert!(extract_method_signatures(&jar).unwrap().is_empty());
    }

    #[test]
    fn jar_malformato_errora() {
        assert!(extract_method_signatures(b"not a jar at all").is_err());
    }

    /// Costruisce un JAR con una entry compressa (method=8, deflate).
    fn build_jar_deflated(name: &str, data: &[u8]) -> Vec<u8> {
        use std::io::Write;
        let mut enc = flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(data).unwrap();
        let compressed = enc.finish().unwrap();

        let nb = name.as_bytes();
        let mut out = Vec::new();
        let lh_off = out.len();
        put_u32(&mut out, 0x04034B50);
        put_u16(&mut out, 20);
        put_u16(&mut out, 0);
        put_u16(&mut out, 8); // method = deflate
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u32(&mut out, 0);
        put_u32(&mut out, compressed.len() as u32);
        put_u32(&mut out, data.len() as u32);
        put_u16(&mut out, nb.len() as u16);
        put_u16(&mut out, 0);
        out.extend_from_slice(nb);
        out.extend_from_slice(&compressed);

        let cd_off = out.len();
        put_u32(&mut out, 0x02014B50);
        put_u16(&mut out, 20);
        put_u16(&mut out, 20);
        put_u16(&mut out, 0);
        put_u16(&mut out, 8); // method = deflate
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u32(&mut out, 0);
        put_u32(&mut out, compressed.len() as u32);
        put_u32(&mut out, data.len() as u32);
        put_u16(&mut out, nb.len() as u16);
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u32(&mut out, 0);
        put_u32(&mut out, lh_off as u32);
        out.extend_from_slice(nb);

        let cd_size = out.len() - cd_off;
        put_u32(&mut out, 0x06054B50);
        put_u16(&mut out, 0);
        put_u16(&mut out, 0);
        put_u16(&mut out, 1);
        put_u16(&mut out, 1);
        put_u32(&mut out, cd_size as u32);
        put_u32(&mut out, cd_off as u32);
        put_u16(&mut out, 0);
        out
    }

    #[test]
    fn jar_deflate_estrae_le_firme() {
        let jar = build_jar_deflated("com/example/Test.class", &class_bytes());
        let sigs = extract_method_signatures(&jar).unwrap();
        assert_eq!(sigs.len(), 2);
        assert_eq!(sigs[0].name, "a");
        assert_eq!(sigs[0].descriptor, "()V");
        assert_eq!(sigs[1].name, "b");
        assert_eq!(sigs[1].descriptor, "(Ljava/lang/String;)Z");
    }
}
