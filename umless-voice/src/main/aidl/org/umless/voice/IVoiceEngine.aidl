// 输入法进程 / 主 App ↔ :voice 进程。录音在调用方进程完成（Android 11+ 后台进程不能录音），
// 这里只做识别。音频以文件传递（避免 Binder 1MB 上限）：float32 小端单声道 PCM。
package org.umless.voice;

interface IVoiceEngine {
    /** 加载模型（已加载则立即返回）。返回 null 表示成功，否则为错误信息 */
    String preload(String engine, String modelDir, int numThreads);

    boolean isReady();

    /** 识别 pcmPath 中的音频。返回 JSON：{"text": "...", "asr_ms": 123} 或 {"error": "..."} */
    String transcribe(String pcmPath, int sampleRate);

    /** 释放模型内存 */
    void release();
}
