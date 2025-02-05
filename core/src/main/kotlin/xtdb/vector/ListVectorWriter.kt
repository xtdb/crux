package xtdb.vector

import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.NullVector
import org.apache.arrow.vector.ValueVector
import org.apache.arrow.vector.complex.DenseUnionVector
import org.apache.arrow.vector.complex.ListVector
import org.apache.arrow.vector.complex.replaceDataVector
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import xtdb.arrow.*
import xtdb.toFieldType

class ListVectorWriter(override val vector: ListVector, private val notify: FieldChangeListener?) : IVectorWriter {
    private val wp = VectorPosition.build(vector.valueCount)
    override var field: Field = vector.field

    private fun upsertElField(elField: Field) {
        field = Field(field.name, field.fieldType, listOf(elField))
        notify(field)
    }

    private var elWriter = writerFor(vector.dataVector, ::upsertElField)

    private fun promoteElWriter(fieldType: FieldType): IVectorWriter {
        val newVec = elWriter.promote(fieldType, vector.allocator)
        vector.replaceDataVector(newVec)
        upsertElField(newVec.field)
        return writerFor(newVec, ::upsertElField).also { elWriter = it }
    }

    override fun writerPosition() = wp

    override fun clear() {
        super.clear()
        elWriter.clear()
    }

    override fun writeNull() {
        // see https://github.com/apache/arrow/issues/40796
        super.writeNull()
        vector.lastSet = wp.position - 1
    }

    override fun listElementWriter(): IVectorWriter =
        if (vector.dataVector is NullVector) listElementWriter(UNION_FIELD_TYPE) else elWriter

    override fun listElementWriter(fieldType: FieldType): IVectorWriter {
        val res = vector.addOrGetVector<FieldVector>(fieldType)
        if (!res.isCreated) return elWriter

        val newDataVec = res.vector
        upsertElField(newDataVec.field)
        elWriter = writerFor(newDataVec, ::upsertElField)
        return elWriter
    }

    override fun startList() {
        vector.startNewValue(wp.position)
    }

    override fun endList() {
        val pos = wp.getPositionAndIncrement()
        val endPos = elWriter.writerPosition().position
        vector.endValue(pos, endPos - vector.getElementStartIndex(pos))
    }

    private inline fun writeList(f: () -> Unit) {
        startList(); f(); endList()
    }

    override fun writeObject0(obj: Any) {
        writeList {
            when (obj) {
                is ListValueReader ->
                    for (i in 0..<obj.size()) {
                        try {
                            elWriter.writeValue(obj.nth(i))
                        } catch (e: InvalidWriteObjectException) {
                            promoteElWriter(e.obj.toFieldType()).writeObject(obj.nth(i))
                        }
                    }

                is List<*> -> obj.forEach {
                    try {
                        elWriter.writeObject(it)
                    } catch (e: InvalidWriteObjectException) {
                        promoteElWriter(e.obj.toFieldType()).writeObject(it)
                    }
                }

                else -> throw InvalidWriteObjectException(field.fieldType, obj)
            }
        }
    }

    override fun writeValue0(v: ValueReader) = writeObject(v.readObject())

    override fun promoteChildren(field: Field) {
        if (field.type != this.field.type || (field.isNullable && !this.field.isNullable))
            throw FieldMismatch(this.field.fieldType, field.fieldType)
        val child = field.children.single()
        if ((child.type != elWriter.field.type || (child.isNullable && !elWriter.field.isNullable)) && elWriter.field.type !is ArrowType.Union)
            promoteElWriter(child.fieldType)
        if (child.children.isNotEmpty()) elWriter.promoteChildren(child)
    }

    override fun rowCopier(src: ValueVector) = when (src) {
        is NullVector -> nullToVecCopier(this)
        is DenseUnionVector -> duvToVecCopier(this, src)
        is ListVector -> {
            if (src.field.isNullable && !field.isNullable)
                throw InvalidCopySourceException(src.field.fieldType, field.fieldType)

            val innerCopier = listElementWriter().rowCopier(src.dataVector)

            RowCopier { srcIdx ->
                wp.position.also {
                    if (src.isNull(srcIdx)) writeNull()
                    else writeList {
                        for (i in src.getElementStartIndex(srcIdx)..<src.getElementEndIndex(srcIdx)) {
                            innerCopier.copyRow(i)
                        }
                    }
                }
            }
        }

        else -> throw InvalidCopySourceException(src.field.fieldType, field.fieldType)
    }
}
