//! Puente JNI de ESCÁNER PRO MAX sobre `pdfsqueeze-core` (submódulo `native/pdfsqueeze`).
//!
//! Diseño pensado para teléfonos (velocidad, memoria y batería):
//!  - API por rutas: la entrada se mapea en memoria (mmap) y la salida se escribe directamente a un archivo;
//!    nada de copiar `byte[]` por el heap de Java.
//!  - Un "job" por llamada (`jobNew`/`jobFree`) con su propio progreso y cancelación: varias compresiones a la vez
//!    no se pisan (antes el progreso/cancelación eran globales).
//!  - Un pool de rayon propio por llamada con N hilos y prioridad de fondo (nice), así no compite con la UI.
//!  - `catch_unwind` en todas las entradas: un pánico del núcleo se convierte en un error, nunca tumba la app.
//!
//! Todas las funciones devuelven un sobre JSON: `{"status":N,"message":"...", ...}` con
//! status 0 = ok, 1 = entrada/opciones no válidas, 2 = cifrado, 3 = error interno, 4 = cancelado, 5 = E/S.

use pdfsqueeze_core::pipeline::{compress_with, Progress};
use pdfsqueeze_core::{Options, Profile};
use serde_json::{json, Value};
use std::fs::File;
use std::io::Write;
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};

pub const STATUS_OK: i32 = 0;
pub const STATUS_BAD_INPUT: i32 = 1;
pub const STATUS_ENCRYPTED: i32 = 2;
pub const STATUS_INTERNAL: i32 = 3;
pub const STATUS_CANCELLED: i32 = 4;
pub const STATUS_IO: i32 = 5;

/// Estado compartido de una compresión: progreso (0..=100), imágenes hechas/total y cancelación.
#[derive(Default)]
pub struct Job {
    pub progress: AtomicU32,
    pub images_done: AtomicU32,
    pub images_total: AtomicU32,
    pub cancel: AtomicBool,
}

pub fn profile_from_str(p: &str) -> Profile {
    match p.trim().to_ascii_lowercase().as_str() {
        "lossless" => Profile::Lossless,
        "small" => Profile::Small,
        "extreme" => Profile::Extreme,
        _ => Profile::Balanced,
    }
}

/// Opciones desde JSON: `{"profile":"small", ...}` parte de ese preset y superpone el resto de claves.
pub fn options_from_json(json: &str) -> Result<Options, String> {
    let s = json.trim();
    if s.is_empty() {
        return Ok(Options::default());
    }
    let v: Value = serde_json::from_str(s).map_err(|e| e.to_string())?;
    let profile = v.get("profile").and_then(Value::as_str).map(profile_from_str).unwrap_or_default();
    let mut merged = serde_json::to_value(Options::from_profile(profile)).map_err(|e| e.to_string())?;
    if let (Some(m), Some(o)) = (merged.as_object_mut(), v.as_object()) {
        for (k, val) in o {
            m.insert(k.clone(), val.clone());
        }
    }
    serde_json::from_value(merged).map_err(|e| e.to_string())
}

pub fn options_json(profile: &str) -> String {
    serde_json::to_string(&Options::from_profile(profile_from_str(profile))).unwrap_or_default()
}

fn envelope(status: i32, message: impl Into<String>) -> Value {
    json!({ "status": status, "message": message.into() })
}

/// Baja la prioridad del hilo actual (Linux/Android: setpriority por tid afecta solo a ese hilo).
fn set_thread_nice(nice: i32) {
    if nice <= 0 {
        return;
    }
    #[cfg(any(target_os = "android", target_os = "linux"))]
    unsafe {
        let tid = libc::gettid();
        let _ = libc::setpriority(libc::PRIO_PROCESS, tid as _, nice.min(19));
    }
}

fn map_input(path: &str) -> Result<memmap2::Mmap, Value> {
    let f = File::open(path).map_err(|e| envelope(STATUS_IO, format!("no se pudo abrir la entrada: {e}")))?;
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    if len < 8 {
        return Err(envelope(STATUS_BAD_INPUT, "archivo vacío"));
    }
    // SAFETY: el archivo es una copia privada en la caché de la app; nadie lo trunca mientras se lee.
    unsafe { memmap2::Mmap::map(&f) }.map_err(|e| envelope(STATUS_IO, format!("mmap: {e}")))
}

/// Escribe en `output.part` y renombra: nunca queda un archivo de salida a medias.
fn write_output(output: &str, data: &[u8]) -> std::io::Result<()> {
    let tmp = format!("{output}.part");
    {
        let mut f = File::create(&tmp)?;
        f.write_all(data)?;
        f.flush()?;
    }
    std::fs::rename(&tmp, output).inspect_err(|_| {
        let _ = std::fs::remove_file(&tmp);
    })
}

/// Comprime `input` → `output` con un pool propio de `threads` hilos a prioridad `nice`.
/// Si el núcleo devuelve el original (nada lo mejora) NO escribe salida y marca `returned_original`.
pub fn compress_file(job: &Job, input: &str, output: &str, options: &str, threads: usize, nice: i32) -> Value {
    let run = || -> Value {
        let mut opts = match options_from_json(options) {
            Ok(o) => o,
            Err(e) => return envelope(STATUS_BAD_INPUT, format!("opciones no válidas: {e}")),
        };
        // El pool lo creamos aquí: el núcleo no debe tocar el pool global de rayon.
        opts.threads = 0;
        let mmap = match map_input(input) {
            Ok(m) => m,
            Err(e) => return e,
        };
        let threads = threads.max(1);
        let pool = match rayon::ThreadPoolBuilder::new()
            .num_threads(threads)
            .thread_name(|i| format!("pdfsqueeze-{i}"))
            .start_handler(move |_| set_thread_nice(nice))
            .build()
        {
            Ok(p) => p,
            Err(e) => return envelope(STATUS_INTERNAL, format!("pool: {e}")),
        };
        let progress = |p: Progress| -> bool {
            job.progress.fetch_max(p.percent as u32, Ordering::Relaxed);
            if p.images_total > 0 {
                job.images_total.store(p.images_total, Ordering::Relaxed);
                job.images_done.fetch_max(p.images_done, Ordering::Relaxed);
            }
            !job.cancel.load(Ordering::Relaxed)
        };
        let result = pool.install(|| compress_with(&mmap[..], &opts, &progress));
        drop(pool);
        match result {
            Ok((out, report)) => {
                let returned_original = report.returned_original || out.len() >= mmap.len();
                if !returned_original {
                    if let Err(e) = write_output(output, &out) {
                        return envelope(STATUS_IO, format!("no se pudo escribir la salida: {e}"));
                    }
                }
                drop(out);
                let mut env = envelope(STATUS_OK, "ok");
                env["returned_original"] = Value::Bool(returned_original);
                env["threads"] = json!(threads);
                env["report"] = serde_json::to_value(&report).unwrap_or(Value::Null);
                env
            }
            Err(pdfsqueeze_core::Error::Encrypted) => envelope(STATUS_ENCRYPTED, "PDF cifrado"),
            Err(pdfsqueeze_core::Error::Cancelled) => envelope(STATUS_CANCELLED, "cancelado"),
            Err(pdfsqueeze_core::Error::Io(e)) => envelope(STATUS_IO, e.to_string()),
            Err(e) => envelope(STATUS_BAD_INPUT, e.to_string()),
        }
    };
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(run)) {
        Ok(v) => v,
        Err(_) => {
            let _ = std::fs::remove_file(format!("{output}.part"));
            envelope(STATUS_INTERNAL, "error interno del motor")
        }
    }
}

/// Análisis sin comprimir (presupuesto de bytes, imágenes, dpi efectivos...).
pub fn analyze_file(input: &str) -> Value {
    let run = || -> Value {
        let mmap = match map_input(input) {
            Ok(m) => m,
            Err(e) => return e,
        };
        match pdfsqueeze_core::pipeline::load_lenient(&mmap[..]) {
            Ok((doc, _)) => {
                let mut env = envelope(STATUS_OK, "ok");
                env["analysis"] = serde_json::to_value(pdfsqueeze_core::analyze::analyze(&doc, mmap.len() as u64)).unwrap_or(Value::Null);
                env
            }
            Err(pdfsqueeze_core::Error::Encrypted) => envelope(STATUS_ENCRYPTED, "PDF cifrado"),
            Err(e) => envelope(STATUS_BAD_INPUT, e.to_string()),
        }
    };
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(run)).unwrap_or_else(|_| envelope(STATUS_INTERNAL, "error interno del motor"))
}

pub fn version() -> &'static str {
    pdfsqueeze_core::VERSION
}

// ------------------------------------------------------------------------------------------------ JNI

mod jni_api {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::{jint, jlong, jstring};
    use jni::JNIEnv;

    fn to_string(env: &mut JNIEnv, s: &JString) -> String {
        if s.is_null() {
            return String::new();
        }
        env.get_string(s).map(|s| s.into()).unwrap_or_default()
    }

    fn new_string(env: &mut JNIEnv, s: &str) -> jstring {
        env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }

    fn job_ref<'a>(handle: jlong) -> Option<&'a Job> {
        if handle == 0 {
            None
        } else {
            // SAFETY: el handle viene de jobNew y Kotlin no lo libera mientras haya una llamada en curso.
            Some(unsafe { &*(handle as *const Job) })
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_jobNew(_env: JNIEnv, _cls: JClass) -> jlong {
        Box::into_raw(Box::new(Job::default())) as jlong
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_jobFree(_env: JNIEnv, _cls: JClass, handle: jlong) {
        if handle != 0 {
            // SAFETY: handle de jobNew, liberado una sola vez (Kotlin lo garantiza).
            drop(unsafe { Box::from_raw(handle as *mut Job) });
        }
    }

    /// Progreso 0..=100 del job.
    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_jobProgress(_env: JNIEnv, _cls: JClass, handle: jlong) -> jint {
        job_ref(handle).map(|j| j.progress.load(Ordering::Relaxed) as jint).unwrap_or(0)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_jobCancel(_env: JNIEnv, _cls: JClass, handle: jlong) {
        if let Some(j) = job_ref(handle) {
            j.cancel.store(true, Ordering::Relaxed);
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_compressFile<'l>(
        mut env: JNIEnv<'l>,
        _cls: JClass<'l>,
        handle: jlong,
        input: JString<'l>,
        output: JString<'l>,
        options: JString<'l>,
        threads: jint,
        nice: jint,
    ) -> jstring {
        let input = to_string(&mut env, &input);
        let output = to_string(&mut env, &output);
        let options = to_string(&mut env, &options);
        let local = Job::default();
        let job = job_ref(handle).unwrap_or(&local);
        let v = compress_file(job, &input, &output, &options, threads.max(1) as usize, nice);
        new_string(&mut env, &v.to_string())
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_analyzeFile<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, input: JString<'l>) -> jstring {
        let input = to_string(&mut env, &input);
        let v = analyze_file(&input);
        new_string(&mut env, &v.to_string())
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_optionsJson<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>, profile: JString<'l>) -> jstring {
        let p = to_string(&mut env, &profile);
        new_string(&mut env, &options_json(&p))
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_pdfsqueeze_PdfSqueeze_version<'l>(mut env: JNIEnv<'l>, _cls: JClass<'l>) -> jstring {
        new_string(&mut env, version())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overlay_keeps_preset_and_overrides() {
        let o = options_from_json(r#"{"profile":"small","min_ssim":0.9,"zopfli_iterations":0}"#).unwrap();
        assert_eq!(o.profile, Profile::Small);
        assert!((o.min_ssim - 0.9).abs() < 1e-9);
        assert_eq!(o.zopfli_iterations, 0);
        assert!(o.mrc);
    }

    #[test]
    fn bad_input_is_an_error_not_a_panic() {
        let dir = std::env::temp_dir().join(format!("psq_test_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let inp = dir.join("bad.pdf");
        std::fs::write(&inp, b"not a pdf at all, really").unwrap();
        let out = dir.join("out.pdf");
        let v = compress_file(&Job::default(), inp.to_str().unwrap(), out.to_str().unwrap(), "{}", 1, 10);
        assert_ne!(v["status"].as_i64().unwrap(), 0);
        assert!(!out.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn compresses_generated_pdf() {
        // Un PDF con una imagen grande generado por las utilidades de prueba del núcleo.
        use pdfsqueeze_core::testgen::{photo, ImgEnc, Synth};
        let mut s = Synth::new();
        s.text_page(20);
        s.image_page(&photo(1200, 900), ImgEnc::Flate, 0.0, 0.0, 595.0, 446.0);
        let pdf = s.finish();
        let dir = std::env::temp_dir().join(format!("psq_test2_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let inp = dir.join("in.pdf");
        std::fs::write(&inp, &pdf).unwrap();
        let out = dir.join("out.pdf");
        let job = Job::default();
        let v = compress_file(&job, inp.to_str().unwrap(), out.to_str().unwrap(), r#"{"profile":"small","zopfli_iterations":0}"#, 2, 10);
        assert_eq!(v["status"].as_i64().unwrap(), 0, "{v}");
        if !v["returned_original"].as_bool().unwrap() {
            assert!(std::fs::metadata(&out).unwrap().len() < pdf.len() as u64);
        }
        assert_eq!(job.progress.load(Ordering::Relaxed), 100);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
