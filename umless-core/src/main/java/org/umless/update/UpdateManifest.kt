package org.umless.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Android 版本号（spec/update.md 第 5 节）：
 * versionCode = (MAJOR × 1 000 000 + MINOR × 1 000 + PATCH) × 10 + ABI，ABI：armeabi-v7a=1、arm64-v8a=2、x86=3、x86_64=4。
 * 「基础版本号」= versionCode / 10（不含 ABI 位），更新清单的 version_code 就是它。
 * app/build.gradle.kts 里有同样的公式（构建脚本不能引用这个类），改动时两处一起改。
 */
object VersionCodes {
    val ABI_IDS: Map<String, Int> = linkedMapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

    /** MAJOR.MINOR.PATCH，可带 v 前缀与 -beta.1 / +build 之类的后缀（后缀不参与版本号） */
    private val SEMVER = Regex("""^\s*v?(\d+)\.(\d+)\.(\d+)(?:[-+].*)?\s*$""")

    /** 版本名 → 基础版本号；格式不对或 MINOR / PATCH 超过 999 时为 null */
    fun base(version: String): Long? {
        val m = SEMVER.matchEntire(version) ?: return null
        val (major, minor, patch) = m.destructured
        val ma = major.toLongOrNull() ?: return null
        val mi = minor.toLongOrNull()?.takeIf { it < 1000 } ?: return null
        val pa = patch.toLongOrNull()?.takeIf { it < 1000 } ?: return null
        return ma * 1_000_000 + mi * 1_000 + pa
    }

    /** 版本名 + ABI → 完整 versionCode；未知 ABI 的 ABI 位为 0 */
    fun full(version: String, abi: String?): Long? = base(version)?.let { it * 10 + (ABI_IDS[abi] ?: 0) }

    /** 完整 versionCode → 基础版本号 */
    fun baseOf(versionCode: Long): Long = versionCode / 10

    /** 完整 versionCode 的 ABI 位 → ABI 名；0 或未知时为 null */
    fun abiOf(versionCode: Long): String? {
        val id = (versionCode % 10).toInt()
        return ABI_IDS.entries.firstOrNull { it.value == id }?.key
    }

    /** 清单里的平台名，如 android-arm64-v8a */
    fun platformOf(abi: String): String = "android-$abi"

    /**
     * 选择安装包时 ABI 的优先顺序：先是当前已安装包的 ABI（versionCode 的 ABI 位，且本机支持），
     * 再按系统 Build.SUPPORTED_ABIS 的顺序。这样 arm64 设备上装的 arm64 包不会被换成 32 位包
     */
    fun preferredAbis(installedVersionCode: Long, supportedAbis: List<String>): List<String> {
        val installed = abiOf(installedVersionCode)?.takeIf { it in supportedAbis }
        return (listOfNotNull(installed) + supportedAbis).distinct()
    }
}

/** 清单里的一个安装包 */
class UpdateAsset(
    val platform: String,
    val kind: String,
    val name: String,
    val url: String,
    val size: Long?,
    /** 小写十六进制；清单里没有时为 null（这样的安装包不能下载） */
    val sha256: String?,
    /** 该 ABI 安装包的完整 versionCode */
    val versionCode: Long?,
) {
    /** android-arm64-v8a → arm64-v8a */
    val abi: String? get() = platform.removePrefix("android-").takeIf { platform.startsWith("android-") }
}

/**
 * 更新清单 update.json（spec/update.md 第 3 节）。未知字段忽略；schema 比已知的大时仍按已知字段解析。
 * [raw] 是原始 JSON，缓存在本地数据库里，下次直接重新解析。
 */
class UpdateManifest(
    val schema: Int,
    val app: String?,
    val version: String,
    val versionCode: Long?,
    val publishedAt: String?,
    val notes: Map<String, String>,
    val assets: List<UpdateAsset>,
    val raw: JsonObject,
) {
    /** 基础版本号：清单的 version_code，没有时由 version 计算 */
    val baseVersionCode: Long? get() = versionCode ?: VersionCodes.base(version)

    /** 按界面语言（zh / en）取更新说明，缺失时用另一种 */
    fun notesFor(lang: String): String {
        val other = if (lang == "zh") "en" else "zh"
        return notes[lang]?.takeIf { it.isNotBlank() } ?: notes[other]?.takeIf { it.isNotBlank() }
            ?: notes.values.firstOrNull { it.isNotBlank() } ?: ""
    }

    /** 按 ABI 优先顺序取第一个匹配的 apk；没有匹配项表示「没有适合本机的更新」 */
    fun assetFor(abis: List<String>): UpdateAsset? = abis.firstNotNullOfOrNull { abi ->
        assets.firstOrNull { it.kind == "apk" && it.platform == VersionCodes.platformOf(abi) && it.url.isNotBlank() }
    }

    companion object {
        const val SCHEMA = 1
        const val APP = "umless-android"

        /** 解析失败抛出 [IllegalArgumentException] */
        fun parse(text: String): UpdateManifest {
            val element = try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw IllegalArgumentException("not JSON", e)
            }
            return fromJson(element as? JsonObject ?: throw IllegalArgumentException("not an object"))
        }

        fun fromJson(obj: JsonObject): UpdateManifest {
            val schema = obj["schema"]?.let { long(it) ?: throw IllegalArgumentException("bad schema") } ?: SCHEMA.toLong()
            if (schema < 1) throw IllegalArgumentException("bad schema: $schema")
            val app = string(obj["app"])
            // 清单地址被改错（例如指向桌面端的清单）时不能把别的应用当成更新
            if (app != null && app != APP) throw IllegalArgumentException("manifest is for $app")
            val version = string(obj["version"])?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("missing version")
            val notes = when (val n = obj["notes"]) {
                is JsonObject -> n.mapNotNull { (k, v) -> string(v)?.let { k to it } }.toMap()
                is JsonPrimitive -> string(n)?.let { mapOf("zh" to it, "en" to it) }.orEmpty()
                else -> emptyMap()
            }
            val assets = (obj["assets"] as? JsonArray).orEmpty().mapNotNull { e ->
                val a = e as? JsonObject ?: return@mapNotNull null
                val platform = string(a["platform"]) ?: return@mapNotNull null
                UpdateAsset(
                    platform = platform,
                    kind = string(a["kind"]) ?: "",
                    name = string(a["name"]) ?: "",
                    url = string(a["url"])?.trim() ?: "",
                    size = a["size"]?.let(::long)?.takeIf { it > 0 },
                    sha256 = string(a["sha256"])?.trim()?.lowercase(Locale.ROOT)?.takeIf { SHA256.matches(it) },
                    versionCode = a["version_code"]?.let(::long)?.takeIf { it > 0 },
                )
            }
            return UpdateManifest(
                schema = schema.toInt(),
                app = app,
                version = version,
                versionCode = obj["version_code"]?.let(::long)?.takeIf { it > 0 },
                publishedAt = string(obj["published_at"]),
                notes = notes,
                assets = assets,
                raw = obj,
            )
        }

        private val SHA256 = Regex("^[0-9a-f]{64}$")

        private fun string(e: JsonElement?): String? =
            (e as? JsonPrimitive)?.takeIf { it.isString && it !is JsonNull }?.content

        /** 整数（也接受 "2000" 这样的字符串与 2000.0） */
        private fun long(e: JsonElement): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            p.content.trim().toLongOrNull()?.let { return it }
            return p.content.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it == Math.floor(it) }?.toLong()
        }
    }
}
