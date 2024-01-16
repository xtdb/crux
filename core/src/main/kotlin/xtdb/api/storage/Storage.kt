@file:JvmName("Storage")

package xtdb.api.storage

import org.apache.arrow.memory.BufferAllocator
import xtdb.IBufferPool
import xtdb.util.requiringResolve
import java.nio.file.Path

sealed interface StorageFactory {
    companion object {
        val DEFAULT = InMemoryStorageFactory
    }

    fun openStorage(allocator: BufferAllocator): IBufferPool
}

data object InMemoryStorageFactory : StorageFactory {
    private val OPEN_STORAGE = requiringResolve("xtdb.buffer-pool", "open-in-memory-storage")

    override fun openStorage(allocator: BufferAllocator) = OPEN_STORAGE.invoke(allocator) as IBufferPool
}

class LocalStorageFactory(
    val path: Path,
    var maxCacheEntries: Long = 1024,
    var maxCacheBytes: Long = 536870912,
) : StorageFactory {
    companion object {
        private val OPEN_STORAGE = requiringResolve("xtdb.buffer-pool", "open-local-storage")
    }

    fun maxCacheEntries(maxCacheEntries: Long) = apply { this.maxCacheEntries = maxCacheEntries }
    fun maxCacheBytes(maxCacheBytes: Long) = apply { this.maxCacheBytes = maxCacheBytes }

    override fun openStorage(allocator: BufferAllocator) = OPEN_STORAGE.invoke(allocator, this) as IBufferPool
}

fun local(path: Path) = LocalStorageFactory(path)

@JvmSynthetic
fun local(path: Path, build: LocalStorageFactory.() -> Unit) = LocalStorageFactory(path).also(build)

interface ObjectStoreFactory {
    fun openObjectStore(): ObjectStore
}

class RemoteStorageFactory(
    val objectStore: ObjectStoreFactory,
    val localDiskCache: Path,
    var maxCacheEntries: Long = 1024,
    var maxCacheBytes: Long = 536870912,
) : StorageFactory {
    companion object {
        private val OPEN_STORAGE = requiringResolve("xtdb.buffer-pool", "open-remote-storage")
    }

    fun maxCacheEntries(maxCacheEntries: Long) = apply { this.maxCacheEntries = maxCacheEntries }
    fun maxCacheBytes(maxCacheBytes: Long) = apply { this.maxCacheBytes = maxCacheBytes }

    override fun openStorage(allocator: BufferAllocator) = OPEN_STORAGE.invoke(allocator, this) as IBufferPool
}

fun remote(objectStore: ObjectStoreFactory, localDiskCachePath: Path) =
    RemoteStorageFactory(objectStore, localDiskCachePath)

@JvmSynthetic
fun remote(objectStore: ObjectStoreFactory, localDiskCachePath: Path, build: RemoteStorageFactory.() -> Unit) =
    RemoteStorageFactory(objectStore, localDiskCachePath).also(build)

