package xtdb.operator

import clojure.lang.Symbol
import org.apache.arrow.memory.BufferAllocator
import xtdb.vector.RelationReader
import xtdb.vector.IVectorReader

interface ProjectionSpec {
    val columnName: Symbol
    val columnType: Any

    /**
     * @param args a single-row indirect relation containing the args for this invocation - maybe a view over a bigger arg relation.
     */
    fun project(allocator: BufferAllocator, readRelation: RelationReader, schema: Map<String, Any>, args: RelationReader): IVectorReader
}
