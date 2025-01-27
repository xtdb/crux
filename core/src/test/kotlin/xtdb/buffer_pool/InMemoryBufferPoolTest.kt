package xtdb.buffer_pool

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import xtdb.IBufferPool
import xtdb.api.storage.Storage.localStorage
import java.nio.file.Files.createTempDirectory

class InMemoryBufferPoolTest : BufferPoolTest() {
    override fun bufferPool(): IBufferPool = localBufferPool

    private lateinit var allocator: BufferAllocator
    private lateinit var localBufferPool: LocalBufferPool

    @BeforeEach
    fun setUp() {
        allocator = RootAllocator()
        localBufferPool = LocalBufferPool(allocator, localStorage(createTempDirectory("local-buffer-pool-test")))
    }

    @AfterEach
    fun tearDown() {
        localBufferPool.close()
        allocator.close()
    }
}