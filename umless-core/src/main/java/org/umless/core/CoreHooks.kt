package org.umless.core

import kotlinx.serialization.json.JsonObject

/** 由上层模块注入的能力（core 不依赖 umless-voice）。modelsInfo 由 org.umless.voice.ModelManager.registerHooks 设置 */
object CoreHooks {
    /** get_settings().models，如 {"sensevoice": {"downloaded": true, "size_mb": 166, "name": "SenseVoice-Small"}} */
    @Volatile
    var modelsInfo: () -> JsonObject = { JsonObject(emptyMap()) }
}
