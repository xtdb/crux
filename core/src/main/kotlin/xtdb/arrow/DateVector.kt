package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.DateUnit.DAY
import org.apache.arrow.vector.types.DateUnit.MILLISECOND
import org.apache.arrow.vector.types.pojo.ArrowType
import xtdb.api.query.IKeyFn
import xtdb.util.Hasher
import java.time.Duration
import java.time.LocalDate

class DateDayVector(
    allocator: BufferAllocator,
    override var name: String,
    nullable: Boolean,
) : FixedWidthVector(allocator, nullable, ArrowType.Date(DAY), Int.SIZE_BYTES) {

    override fun getInt(idx: Int) = getInt0(idx)
    override fun writeInt(value: Int) = writeInt0(value)

    override fun getObject0(idx: Int, keyFn: IKeyFn<*>) = LocalDate.ofEpochDay(getInt(idx).toLong())

    override fun writeObject0(value: Any) {
        if (value is LocalDate) writeInt(value.toEpochDay().toInt())
        else throw InvalidWriteObjectException(fieldType, value)
    }

    override fun hashCode0(idx: Int, hasher: Hasher) = hasher.hash(getInt(idx) * MILLIS_PER_DAY * 1_000_000L)
}

private val MILLIS_PER_DAY = Duration.ofDays(1).toMillis()

class DateMilliVector(
    allocator: BufferAllocator,
    override var name: String,
    nullable: Boolean,
) : FixedWidthVector(allocator, nullable, ArrowType.Date(MILLISECOND), Long.SIZE_BYTES) {

    override fun getLong(idx: Int) = getLong0(idx)
    override fun writeLong(value: Long) = writeLong0(value)

    override fun getObject0(idx: Int, keyFn: IKeyFn<*>) = LocalDate.ofEpochDay(getLong(idx) / MILLIS_PER_DAY)!!

    override fun writeObject0(value: Any) {
        if (value is LocalDate) writeLong(value.toEpochDay() * MILLIS_PER_DAY)
        else throw InvalidWriteObjectException(fieldType, value)
    }

    override fun hashCode0(idx: Int, hasher: Hasher) = hasher.hash(getInt(idx) * 1_000_000L)
}
