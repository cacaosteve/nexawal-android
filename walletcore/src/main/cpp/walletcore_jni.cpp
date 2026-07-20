#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <dlfcn.h>
#include <android/log.h>

#include "monerowalletcore.h"

// Helper: convert a Rust-allocated C string to jstring and free it via walletcore_free_cstr.
static jstring cstr_to_jstring_and_free(JNIEnv* env, char* cstr) {
    if (cstr == nullptr) {
        return nullptr;
    }
    // Copy into a Java string (UTF-8 expected).
    jstring js = env->NewStringUTF(cstr);
    // Always free Rust-allocated string.
    walletcore_free_cstr(cstr);
    return js;
}

static void wc_logi(const std::string& msg) {
    __android_log_write(4 /* ANDROID_LOG_INFO */, "walletcore-jni", msg.c_str());
}

static void wc_logw(const std::string& msg) {
    __android_log_write(5 /* ANDROID_LOG_WARN */, "walletcore-jni", msg.c_str());
}

static std::string jstring_to_std_string(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* utf = env->GetStringUTFChars(s, nullptr);
    if (utf == nullptr) return std::string();
    std::string out(utf);
    env->ReleaseStringUTFChars(s, utf);
    return out;
}

static jint throw_walletcore_exception(JNIEnv* env, const char* context, int32_t rc) {
    // Compose a detailed message using core last error.
    std::string msg = std::string(context ? context : "walletcore error") + " (rc=" + std::to_string(rc) + ")";
    char* err = walletcore_last_error_message();
    if (err != nullptr) {
        msg += ": ";
        msg += err;
        walletcore_free_cstr(err);
    }

    jclass exCls = env->FindClass("java/lang/RuntimeException");
    if (exCls != nullptr) {
        env->ThrowNew(exCls, msg.c_str());
    }
    return rc;
}

static bool check_rc_or_throw(JNIEnv* env, int32_t rc, const char* context) {
    if (rc == 0) return true;
    throw_walletcore_exception(env, context, rc);
    return false;
}

extern "C" {

// Kotlin/Java signature:
//   package com.nexatrode.nexawal.walletcore
//   internal object WalletCoreJni { external fun version(): String }
// JNI name maps to: Java_<pkg>_<Class>_<method>
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_version(JNIEnv* env, jclass /*clazz*/) {
    // walletcore_version returns newly allocated char* that must be freed.
    char* ver = walletcore_version();
    if (ver == nullptr) {
        // Try to surface a better error string from the core.
        char* err = walletcore_last_error_message();
        if (err != nullptr) {
            return cstr_to_jstring_and_free(env, err);
        }
        return env->NewStringUTF("walletcore_version returned null");
    }
    return cstr_to_jstring_and_free(env, ver);
}

JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_generateMnemonicEnglish(JNIEnv* env, jclass /*clazz*/) {
    using wallet_generate_mnemonic_english_fn = int32_t (*)(char*, size_t, size_t*);
    auto* fn = reinterpret_cast<wallet_generate_mnemonic_english_fn>(
        dlsym(RTLD_DEFAULT, "wallet_generate_mnemonic_english")
    );
    if (fn == nullptr) {
        jclass exCls = env->FindClass("java/lang/UnsupportedOperationException");
        if (exCls != nullptr) {
            env->ThrowNew(exCls, "wallet_generate_mnemonic_english is not exported by this Android wallet core build");
        }
        return nullptr;
    }

    std::vector<char> buf(512, 0);
    size_t written = 0;
    int32_t rc = fn(buf.data(), static_cast<size_t>(buf.size()), &written);
    if (!check_rc_or_throw(env, rc, "wallet_generate_mnemonic_english")) {
        return nullptr;
    }
    if (written == 0) {
        throw_walletcore_exception(env, "wallet_generate_mnemonic_english", -1);
        return nullptr;
    }
    buf[buf.size() - 1] = '\0';
    return env->NewStringUTF(buf.data());
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun lastErrorMessage(): String? }
// Returns: core last error message as UTF-8 string, or null if none.
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_lastErrorMessage(JNIEnv* env, jclass /*clazz*/) {
    // walletcore_last_error_message returns newly allocated char* that must be freed.
    char* err = walletcore_last_error_message();
    if (err == nullptr) {
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, err);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun primaryAddressFromMnemonic(mnemonic: String, mainnet: Boolean): String }
// Returns: Monero primary address as UTF-8 string.
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_primaryAddressFromMnemonic(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring mnemonic,
    jboolean mainnet
) {
    const std::string m = jstring_to_std_string(env, mnemonic);
    const uint8_t is_mainnet = mainnet ? 1 : 0;

    // Match iOS behavior (MoneroWalletCoreFFI.swift):
    // - Do NOT probe required size for wallet_primary_address_from_mnemonic.
    // - Call once with a fixed-size buffer that is large enough for Monero addresses.
    // iOS uses 192 bytes; we mirror that here for parity.
    std::vector<char> buf(192, 0);
    size_t written = 0;

    int32_t rc = wallet_primary_address_from_mnemonic(
        m.c_str(),
        is_mainnet,
        buf.data(),
        static_cast<size_t>(buf.size()),
        &written
    );

    wc_logi(
        "primaryAddressFromMnemonic single-call rc=" + std::to_string(rc) +
        " written=" + std::to_string(written) +
        " buf_cap=" + std::to_string(buf.size()) +
        " mainnet=" + std::to_string(static_cast<int>(is_mainnet)) +
        " mnemonic_len=" + std::to_string(m.size())
    );

    if (!check_rc_or_throw(env, rc, "wallet_primary_address_from_mnemonic")) {
        return nullptr;
    }

    if (written == 0) {
        char* err = walletcore_last_error_message();
        std::string msg = "wallet_primary_address_from_mnemonic wrote 0 bytes";
        if (err != nullptr) {
            msg += ": ";
            msg += err;
            walletcore_free_cstr(err);
        }
        wc_logw(msg);

        jclass exCls = env->FindClass("java/lang/RuntimeException");
        if (exCls != nullptr) {
            env->ThrowNew(exCls, msg.c_str());
        }
        return nullptr;
    }

    // Ensure NUL termination (defensive).
    buf[buf.size() - 1] = '\0';
    return env->NewStringUTF(buf.data());
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun subaddressFromMnemonic(mnemonic: String, accountIndex: Int, subaddressIndex: Int, mainnet: Boolean): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_subaddressFromMnemonic(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring mnemonic,
    jint accountIndex,
    jint subaddressIndex,
    jboolean mainnet
) {
    const std::string m = jstring_to_std_string(env, mnemonic);
    const uint32_t account_index = static_cast<uint32_t>(accountIndex < 0 ? 0 : accountIndex);
    const uint32_t subaddress_index = static_cast<uint32_t>(subaddressIndex < 0 ? 0 : subaddressIndex);
    const uint8_t is_mainnet = mainnet ? 1 : 0;

    std::vector<char> buf(192, 0);
    size_t written = 0;

    int32_t rc = wallet_derive_subaddress_from_mnemonic(
        m.c_str(),
        account_index,
        subaddress_index,
        is_mainnet,
        buf.data(),
        static_cast<size_t>(buf.size()),
        &written
    );

    if (!check_rc_or_throw(env, rc, "wallet_derive_subaddress_from_mnemonic")) {
        return nullptr;
    }

    if (written == 0) {
        char* err = walletcore_last_error_message();
        std::string msg = "wallet_derive_subaddress_from_mnemonic wrote 0 bytes";
        if (err != nullptr) {
            msg += ": ";
            msg += err;
            walletcore_free_cstr(err);
        }
        jclass exCls = env->FindClass("java/lang/RuntimeException");
        if (exCls != nullptr) {
            env->ThrowNew(exCls, msg.c_str());
        }
        return nullptr;
    }

    buf[buf.size() - 1] = '\0';
    return env->NewStringUTF(buf.data());
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun exportOutputsJson(walletId: String): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_exportOutputsJson(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    char* json = wallet_export_outputs_json(wid.c_str());
    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_export_outputs_json", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun openFromMnemonic(walletId: String, mnemonic: String, restoreHeight: Long, mainnet: Boolean) }
JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_openFromMnemonic(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring mnemonic,
    jlong restoreHeight,
    jboolean mainnet
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string m = jstring_to_std_string(env, mnemonic);

    const uint8_t is_mainnet = mainnet ? 1 : 0;

    int32_t rc = wallet_open_from_mnemonic(
        wid.c_str(),
        m.c_str(),
        static_cast<uint64_t>(restoreHeight),
        is_mainnet
    );
    (void)check_rc_or_throw(env, rc, "wallet_open_from_mnemonic");
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun refreshAsync(walletId: String, nodeUrl: String?) }
JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_refreshAsync(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    int32_t rc = wallet_refresh_async(wid.c_str(), url_ptr);
    (void)check_rc_or_throw(env, rc, "wallet_refresh_async");
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun refresh(walletId: String, nodeUrl: String?): Long }
// Returns: lastScanned height (jlong)
JNIEXPORT jlong JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_refresh(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    uint64_t out_last_scanned = 0;
    int32_t rc = wallet_refresh(wid.c_str(), url_ptr, &out_last_scanned);
    if (!check_rc_or_throw(env, rc, "wallet_refresh")) {
        return 0;
    }
    return static_cast<jlong>(out_last_scanned);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun setGapLimit(walletId: String, gapLimit: Int) }
// Calls:
//   int32_t wallet_set_gap_limit(const char* wallet_id, uint32_t gap_limit)
JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_setGapLimit(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jint gapLimit
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    uint32_t gl = static_cast<uint32_t>(gapLimit < 0 ? 0 : gapLimit);

    int32_t rc = wallet_set_gap_limit(wid.c_str(), gl);
    (void)check_rc_or_throw(env, rc, "wallet_set_gap_limit");
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun setEnv(key: String, value: String) }
// Notes:
// - Best-effort; throws RuntimeException on failure.
// - Used to set WALLETCORE_ACCOUNT_GAP (account lookahead) like iOS does.
JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_setEnv(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring key,
    jstring value
) {
    const std::string k = jstring_to_std_string(env, key);
    const std::string v = jstring_to_std_string(env, value);

    if (k.empty()) {
        jclass exCls = env->FindClass("java/lang/IllegalArgumentException");
        if (exCls != nullptr) {
            env->ThrowNew(exCls, "setEnv: key must not be empty");
        }
        return;
    }

#if defined(_WIN32)
    // Not expected on Android; keep for completeness.
    int rc = _putenv_s(k.c_str(), v.c_str());
    if (rc != 0) {
        throw_walletcore_exception(env, "setEnv failed", rc);
    }
#else
    // overwrite=1 to match iOS behavior (setenv(..., 1)).
    int rc = ::setenv(k.c_str(), v.c_str(), 1);
    if (rc != 0) {
        // errno details aren't surfaced here; caller can inspect core lastError separately if needed.
        jclass exCls = env->FindClass("java/lang/RuntimeException");
        if (exCls != nullptr) {
            env->ThrowNew(exCls, "setEnv failed");
        }
    }
#endif
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun refreshCancel(walletId: String) }
JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_refreshCancel(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    int32_t rc = wallet_refresh_cancel(wid.c_str());
    (void)check_rc_or_throw(env, rc, "wallet_refresh_cancel");
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun syncStatus(walletId: String): LongArray }
// Returns: long[5] = [chainHeight, chainTime, lastRefreshTimestamp, lastScanned, restoreHeight]
JNIEXPORT jlongArray JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_syncStatus(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);

    uint64_t chain_height = 0;
    uint64_t chain_time = 0;
    uint64_t last_refresh_ts = 0;
    uint64_t last_scanned = 0;
    uint64_t restore_height = 0;

    int32_t rc = wallet_sync_status(
        wid.c_str(),
        &chain_height,
        &chain_time,
        &last_refresh_ts,
        &last_scanned,
        &restore_height
    );

    if (!check_rc_or_throw(env, rc, "wallet_sync_status")) {
        return nullptr;
    }

    jlongArray arr = env->NewLongArray(5);
    if (arr == nullptr) return nullptr;

    jlong vals[5];
    vals[0] = static_cast<jlong>(chain_height);
    vals[1] = static_cast<jlong>(chain_time);
    vals[2] = static_cast<jlong>(last_refresh_ts);
    vals[3] = static_cast<jlong>(last_scanned);
    vals[4] = static_cast<jlong>(restore_height);

    env->SetLongArrayRegion(arr, 0, 5, vals);
    return arr;
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun importCache(walletId: String, cache: ByteArray) }
JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_importCache(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jbyteArray cache
) {
    const std::string wid = jstring_to_std_string(env, walletId);

    if (cache == nullptr) {
        // Treat null as no-op (caller error), but keep it predictable.
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "cache must not be null");
        return;
    }

    const jsize len = env->GetArrayLength(cache);
    std::vector<uint8_t> buf(static_cast<size_t>(len));

    if (len > 0) {
        env->GetByteArrayRegion(cache, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    }

    int32_t rc = wallet_import_cache(
        wid.c_str(),
        buf.empty() ? nullptr : buf.data(),
        static_cast<size_t>(buf.size())
    );

    (void)check_rc_or_throw(env, rc, "wallet_import_cache");
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun exportCache(walletId: String): ByteArray? }
JNIEXPORT jbyteArray JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_exportCache(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);

    // Phase 1: probe required size
    size_t required = 0;
    int32_t rc_probe = wallet_export_cache(
        wid.c_str(),
        nullptr,
        0,
        &required
    );

    // NOTE: wallet_export_cache uses a two-phase API:
    // - Probe call returns -12 ("buffer too small") but sets `required` to the needed size.
    // Treat rc_probe == 0 OR rc_probe == -12 as success for the probe phase.
    if (!(rc_probe == 0 || rc_probe == -12)) {
        // Surface detailed core error as exception.
        (void)check_rc_or_throw(env, rc_probe, "wallet_export_cache probe");
        return nullptr;
    }

    if (required == 0) {
        // No cache available yet (or probe failed to report size).
        return nullptr;
    }

    std::vector<uint8_t> buf(required);

    // Phase 2: fill buffer
    size_t written = 0;
    int32_t rc_fill = wallet_export_cache(
        wid.c_str(),
        buf.data(),
        buf.size(),
        &written
    );

    if (!check_rc_or_throw(env, rc_fill, "wallet_export_cache fill")) {
        return nullptr;
    }

    if (written == 0) {
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(written));
    if (out == nullptr) return nullptr;

    env->SetByteArrayRegion(out, 0, static_cast<jsize>(written), reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun getBalance(walletId: String): LongArray }
// Returns: long[2] = [total_piconero, unlocked_piconero]
JNIEXPORT jlongArray JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_getBalance(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    uint64_t total = 0;
    uint64_t unlocked = 0;

    int32_t rc = wallet_get_balance(wid.c_str(), &total, &unlocked);
    if (!check_rc_or_throw(env, rc, "wallet_get_balance")) {
        return nullptr;
    }

    jlongArray arr = env->NewLongArray(2);
    if (arr == nullptr) return nullptr;

    jlong vals[2];
    vals[0] = static_cast<jlong>(total);
    vals[1] = static_cast<jlong>(unlocked);

    env->SetLongArrayRegion(arr, 0, 2, vals);
    return arr;
}

JNIEXPORT jlongArray JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_getBalanceWithFilter(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring filterJson
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string filter = jstring_to_std_string(env, filterJson);
    const char* filter_ptr = filterJson == nullptr ? nullptr : filter.c_str();

    uint64_t total = 0;
    uint64_t unlocked = 0;
    int32_t rc = wallet_get_balance_with_filter(wid.c_str(), filter_ptr, &total, &unlocked);
    if (!check_rc_or_throw(env, rc, "wallet_get_balance_with_filter")) {
        return nullptr;
    }

    jlongArray arr = env->NewLongArray(2);
    if (arr == nullptr) return nullptr;

    jlong vals[2];
    vals[0] = static_cast<jlong>(total);
    vals[1] = static_cast<jlong>(unlocked);
    env->SetLongArrayRegion(arr, 0, 2, vals);
    return arr;
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun listTransfersJson(walletId: String): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_listTransfersJson(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    char* json = wallet_list_transfers_json(wid.c_str());
    if (json == nullptr) {
        // Treat null as error (core should set last error message).
        throw_walletcore_exception(env, "wallet_list_transfers_json", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// ===== Send / Fee preview / Sweep (Send Max) =====

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun previewFee(walletId: String, nodeUrl: String?, destinationsJson: String, ringLen: Int): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_previewFee(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring destinationsJson,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string dests = jstring_to_std_string(env, destinationsJson);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    char* json = wallet_preview_fee(
        wid.c_str(),
        url_ptr,
        dests.c_str(),
        static_cast<uint8_t>(ringLen)
    );

    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_preview_fee", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_previewFeeWithFilter(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring destinationsJson,
    jstring filterJson,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string dests = jstring_to_std_string(env, destinationsJson);
    const std::string filter = jstring_to_std_string(env, filterJson);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();
    const char* filter_ptr = filterJson == nullptr ? nullptr : filter.c_str();

    char* json = wallet_preview_fee_with_filter(
        wid.c_str(),
        url_ptr,
        dests.c_str(),
        filter_ptr,
        static_cast<uint8_t>(ringLen)
    );
    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_preview_fee_with_filter", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun send(walletId: String, nodeUrl: String?, toAddress: String, amountPiconero: Long, ringLen: Int): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_send(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring toAddress,
    jlong amountPiconero,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string addr = jstring_to_std_string(env, toAddress);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    char* json = wallet_send(
        wid.c_str(),
        url_ptr,
        addr.c_str(),
        static_cast<uint64_t>(amountPiconero),
        static_cast<uint8_t>(ringLen)
    );

    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_send", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun prepareSend(walletId: String, nodeUrl: String?, toAddress: String, amountPiconero: Long, ringLen: Int): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_prepareSend(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring toAddress,
    jlong amountPiconero,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string addr = jstring_to_std_string(env, toAddress);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    char* json = wallet_prepare_send(
        wid.c_str(),
        url_ptr,
        addr.c_str(),
        static_cast<uint64_t>(amountPiconero),
        static_cast<uint8_t>(ringLen)
    );

    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_prepare_send", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun relayPrepared(walletId: String, nodeUrl: String?, preparedJson: String): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_relayPrepared(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring preparedJson
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string payload = jstring_to_std_string(env, preparedJson);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    char* json = wallet_relay_prepared(
        wid.c_str(),
        url_ptr,
        payload.c_str()
    );

    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_relay_prepared", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_sendWithFilter(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring destinationsJson,
    jstring filterJson,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string dests = jstring_to_std_string(env, destinationsJson);
    const std::string filter = jstring_to_std_string(env, filterJson);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();
    const char* filter_ptr = filterJson == nullptr ? nullptr : filter.c_str();

    char* json = wallet_send_with_filter(
        wid.c_str(),
        url_ptr,
        dests.c_str(),
        filter_ptr,
        static_cast<uint8_t>(ringLen)
    );
    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_send_with_filter", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun previewSweep(walletId: String, nodeUrl: String?, toAddress: String, ringLen: Int): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_previewSweep(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring toAddress,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string addr = jstring_to_std_string(env, toAddress);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    char* json = wallet_preview_sweep(
        wid.c_str(),
        url_ptr,
        addr.c_str(),
        static_cast<uint8_t>(ringLen)
    );

    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_preview_sweep", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_previewSweepWithFilter(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring toAddress,
    jstring filterJson,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string addr = jstring_to_std_string(env, toAddress);
    const std::string filter = jstring_to_std_string(env, filterJson);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();
    const char* filter_ptr = filterJson == nullptr ? nullptr : filter.c_str();

    char* json = wallet_preview_sweep_with_filter(
        wid.c_str(),
        url_ptr,
        addr.c_str(),
        filter_ptr,
        static_cast<uint8_t>(ringLen)
    );
    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_preview_sweep_with_filter", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

// Kotlin/Java signature:
//   internal object WalletCoreJni { external fun sweep(walletId: String, nodeUrl: String?, toAddress: String, ringLen: Int): String }
JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_sweep(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring toAddress,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string addr = jstring_to_std_string(env, toAddress);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();

    char* json = wallet_sweep(
        wid.c_str(),
        url_ptr,
        addr.c_str(),
        static_cast<uint8_t>(ringLen)
    );

    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_sweep", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

JNIEXPORT jstring JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_sweepWithFilter(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jstring nodeUrl,
    jstring toAddress,
    jstring filterJson,
    jint ringLen
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    const std::string url = jstring_to_std_string(env, nodeUrl);
    const std::string addr = jstring_to_std_string(env, toAddress);
    const std::string filter = jstring_to_std_string(env, filterJson);

    const char* url_ptr = nodeUrl == nullptr ? nullptr : url.c_str();
    const char* filter_ptr = filterJson == nullptr ? nullptr : filter.c_str();

    char* json = wallet_sweep_with_filter(
        wid.c_str(),
        url_ptr,
        addr.c_str(),
        filter_ptr,
        static_cast<uint8_t>(ringLen)
    );
    if (json == nullptr) {
        throw_walletcore_exception(env, "wallet_sweep_with_filter", -1);
        return nullptr;
    }
    return cstr_to_jstring_and_free(env, json);
}

JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_forceRescanFromHeight(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId,
    jlong fromHeight
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    int32_t rc = wallet_force_rescan_from_height(wid.c_str(), static_cast<uint64_t>(fromHeight));
    (void)check_rc_or_throw(env, rc, "wallet_force_rescan_from_height");
}

JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_resetTrackedOutputs(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring walletId
) {
    const std::string wid = jstring_to_std_string(env, walletId);
    int32_t rc = wallet_reset_tracked_outputs(wid.c_str());
    (void)check_rc_or_throw(env, rc, "wallet_reset_tracked_outputs");
}

JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_startZmqListener(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring endpoint
) {
    const std::string ep = jstring_to_std_string(env, endpoint);
    int32_t rc = wallet_start_zmq_listener(ep.c_str());
    (void)check_rc_or_throw(env, rc, "wallet_start_zmq_listener");
}

JNIEXPORT void JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_stopZmqListener(
    JNIEnv* env,
    jclass /*clazz*/
) {
    int32_t rc = wallet_stop_zmq_listener();
    (void)check_rc_or_throw(env, rc, "wallet_stop_zmq_listener");
}

JNIEXPORT jlong JNICALL
Java_com_nexatrode_nexawal_walletcore_WalletCoreJni_zmqSequence(
    JNIEnv* env,
    jclass /*clazz*/
) {
    uint64_t out_sequence = 0;
    int32_t rc = wallet_zmq_sequence(&out_sequence);
    if (!check_rc_or_throw(env, rc, "wallet_zmq_sequence")) {
        return 0;
    }
    return static_cast<jlong>(out_sequence);
}

} // extern "C"
