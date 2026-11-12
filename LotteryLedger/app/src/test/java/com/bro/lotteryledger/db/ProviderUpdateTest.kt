package com.bro.lotteryledger.db

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 执行 DAO 的真实编辑方法，模拟编辑框拿到旧状态后用户切换开关。 */
class ProviderUpdateTest {
    private fun provider(enabled: Boolean) = AiProviderEntity(
        id = 1, name = "备用", provider = "custom", enabled = enabled,
        baseUrl = "https://example.com/v1", model = "test", apiKeyRef = "saved-key-ref"
    )

    private class StoredProvider(var row: AiProviderEntity?) : AiProviderDao {
        override suspend fun byId(id: Long) = row?.takeIf { it.id == id }
        override suspend fun update(e: AiProviderEntity) { row = e }
        override suspend fun all() = listOfNotNull(row)
        override suspend fun enabled() = all().filter { it.enabled }
        override suspend fun insert(e: AiProviderEntity): Long = error("编辑不应调用插入")
        override suspend fun setEnabled(id: Long, enabled: Boolean) = error("此测试只验证编辑方法")
        override suspend fun delete(id: Long) = error("此测试只验证编辑方法")
    }

    @Test fun `编辑其它字段保留数据库里的停用状态和密钥引用`() = runBlocking {
        val saved = provider(false)
        val dao = StoredProvider(saved)
        dao.updatePreservingEnabled(saved.copy(enabled = true, name = "改过名称", model = "new-model"))
        assertFalse(dao.row!!.enabled)
        assertEquals("改过名称", dao.row!!.name)
        assertEquals("new-model", dao.row!!.model)
        assertEquals(saved.apiKeyRef, dao.row!!.apiKeyRef)
    }

    @Test fun `旧编辑框不能覆盖后来开启的平台状态`() = runBlocking {
        val dao = StoredProvider(provider(true))
        dao.updatePreservingEnabled(provider(false).copy(model = "new-model"))
        assertTrue(dao.row!!.enabled)
        assertEquals("new-model", dao.row!!.model)
    }

    @Test fun `编辑期间配置被删除就不能重新插入`() = runBlocking {
        val dao = StoredProvider(null)
        dao.updatePreservingEnabled(provider(true))
        assertNull(dao.row)
    }
}
