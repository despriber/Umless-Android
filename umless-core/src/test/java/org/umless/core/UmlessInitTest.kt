package org.umless.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.umless.data.SqlDatabase
import java.io.File
import java.nio.file.Files

/** 初始化失败（打开数据库出错）后应回到未初始化状态，之后可以重试 */
class UmlessInitTest {

    private class BrokenDbPlatform(home: File) : UmlessPlatform by TestPlatform(home) {
        override fun openDatabase(file: File): SqlDatabase = throw IllegalStateException("disk full")
    }

    @Test
    fun failedSetupRollsBackAndCanRetry() {
        val dir = Files.createTempDirectory("umless-init").toFile().apply { deleteOnExit() }
        try {
            Umless.init(BrokenDbPlatform(dir))
            fail("expected init to throw")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertFalse(Umless.isInitialized)
        assertNull(Umless.platformOrNull)

        // 重试成功后一切正常
        TestEnv.setup()
        assertTrue(Umless.isInitialized)
        Umless.db.setKv("k", kotlinx.serialization.json.JsonPrimitive("v"))
        assertEquals("v", (Umless.db.getKv("k") as kotlinx.serialization.json.JsonPrimitive).content)
    }
}
