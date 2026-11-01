package org.umless.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class JobsAndPyTest {
    @Before
    fun setUp() {
        TestEnv.setup()
    }

    private fun waitDone(id: String): JsonObject {
        repeat(500) {
            val s = Jobs.status(id)
            if ((s["state"] as JsonPrimitive).content != "running") return s
            Thread.sleep(10)
        }
        error("job $id still running")
    }

    @Test
    fun jobsDedupAndResultReadOnce(): Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val a = Jobs.start(message = "m", key = "k") { p ->
            p(0.5f, "half")
            gate.await()
            JsonPrimitive("done!")
        }
        val id = (a["job_id"] as JsonPrimitive).content
        assertEquals(12, id.length)
        // 同 key 运行中：返回同一个 job_id，不重复启动
        assertEquals(a, Jobs.start(key = "k") { JsonPrimitive("second") })
        // 不同 key 正常启动
        val other = Jobs.start(key = "other") { JsonNull }
        assertNotEquals(a, other)
        Thread.sleep(50)
        val running = Jobs.status(id)
        assertEquals("running", (running["state"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(0.5f), running["progress"])
        assertEquals(JsonPrimitive("half"), running["message"])
        assertEquals(setOf("state", "progress", "message", "result", "error"), running.keys)
        gate.complete(Unit)
        val done = waitDone(id)
        assertEquals("done", (done["state"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive("done!"), done["result"])
        assertEquals(JsonPrimitive(1.0f), done["progress"])
        // 结果只取一次
        val gone = Jobs.status(id)
        assertEquals("error", (gone["state"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive(I18n.t("job.not_found")), gone["error"])
        // 完成后同 key 可以再次启动
        assertNotEquals(a, Jobs.start(key = "k") { JsonNull })

        val failed = Jobs.start { throw IllegalStateException("boom") }
        val f = waitDone((failed["job_id"] as JsonPrimitive).content)
        assertEquals("error", (f["state"] as JsonPrimitive).content)
        assertEquals(JsonPrimitive("boom"), f["error"])
        val noMsg = Jobs.start { throw IllegalStateException() }
        assertEquals(JsonPrimitive("IllegalStateException"), waitDone((noMsg["job_id"] as JsonPrimitive).content)["error"])
        assertEquals(JsonPrimitive(I18n.t("job.processing")), Jobs.start { JsonNull }.let { Jobs.status((it["job_id"] as JsonPrimitive).content)["message"] })
    }

    @Test
    fun pythonCompat() {
        // round：按二进制值 HALF_EVEN
        assertEquals(0.42, Py.round(0.425, 2), 0.0)
        assertEquals(2.0, Py.round(2.5, 0), 0.0)
        assertEquals(76L, Py.roundInt(76.5))
        assertEquals(78L, Py.roundInt(77.5))
        assertEquals("62", Py.fmt0(62.5))
        assertEquals("64", Py.fmt0(63.5))
        assertEquals("60", Py.fmt0(59.99))
        // Neumaier
        assertEquals(0.75, Py.sum(listOf(0.25, 0.15, 0.20, 0.15)), 0.0)
        // repr
        assertEquals("76.0", Py.repr(76.0))
        assertEquals("2.95", Py.repr(2.95))
        assertEquals("0.1", Py.repr(0.1))
        assertEquals("1e-05", Py.repr(1e-5))
        assertEquals("0.0001", Py.repr(1e-4))
        assertEquals("1e+16", Py.repr(1e16))
        assertEquals("1234567890123456.0", Py.repr(1234567890123456.0))
        assertEquals("0.30000000000000004", Py.repr(0.1 + 0.2))
        assertEquals("74.0", Py.repr(3.7 * 20))
        assertEquals("-0.5", Py.repr(-0.5))
        // float()
        assertEquals(3.5, Py.parseFloat(" 3.5 ")!!, 0.0)
        assertEquals(1000.0, Py.parseFloat("1_000")!!, 0.0)
        assertEquals(true, Py.parseFloat("nan")!!.isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, Py.parseFloat("-Infinity")!!, 0.0)
        assertEquals(12.0, Py.parseFloat("１２")!!, 0.0)
        assertNull(Py.parseFloat("abc"))
        assertNull(Py.parseFloat("1__0"))
        assertNull(Py.toFloat(buildJsonArray { add(JsonPrimitive(1)) }))
        assertEquals(1.0, Py.toFloat(JsonPrimitive(true))!!, 0.0)
        assertEquals(1.0, Py.clamp(Double.NaN, 1.0, 5.0), 0.0)
        assertEquals(5.0, Py.clamp(Double.POSITIVE_INFINITY, 1.0, 5.0), 0.0)
        // json.dumps(ensure_ascii=False)
        val o = buildJsonObject {
            put("title", "Explain \"RAG\"\nto a friend\u0001")
            put("n", 1); put("x", 2.5); put("b", true); put("z", JsonNull)
            put("l", buildJsonArray { add(JsonPrimitive("中文")) })
        }
        assertEquals("{\"title\": \"Explain \\\"RAG\\\"\\nto a friend\\u0001\", \"n\": 1, \"x\": 2.5, \"b\": true, \"z\": null, \"l\": [\"中文\"]}", Py.dumps(o))
        // str()
        assertEquals("None", Py.str(JsonNull))
        assertEquals("True", Py.str(JsonPrimitive(true)))
        assertEquals("76.0", Py.str(JsonPrimitive(76.0)))
        assertEquals("0", Py.str(JsonPrimitive(0)))
        // 码点
        assertEquals(3, Py.len("𠮷野家"))
        assertEquals("𠮷野", Py.take("𠮷野家", 2))
    }
}
