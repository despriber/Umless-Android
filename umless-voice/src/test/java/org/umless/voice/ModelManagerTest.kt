package org.umless.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.umless.core.DownloadSources

class ModelManagerTest {
    /** 下载源测速用的地址必须就是模型的官方下载地址（GitHub，可走镜像） */
    @Test
    fun probeUrlIsSenseVoiceArchive() {
        assertEquals(DownloadSources.PROBE_URL, ModelManager.url("sensevoice"))
        assertTrue(DownloadSources.isGithub(ModelManager.url("sensevoice")))
    }
}
