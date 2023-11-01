package xtdb.vector;

import clojure.java.api.Clojure;
import clojure.lang.*;
import org.apache.arrow.memory.util.ArrowBufPointer;
import org.apache.arrow.memory.util.hash.ArrowBufHasher;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.complex.DenseUnionVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.holders.NullableIntervalDayHolder;
import org.apache.arrow.vector.holders.NullableIntervalMonthDayNanoHolder;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import xtdb.types.ClojureForm;
import xtdb.types.IntervalDayTime;
import xtdb.types.IntervalMonthDayNano;
import xtdb.types.IntervalYearMonth;
import xtdb.vector.extensions.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.time.temporal.ChronoUnit.MICROS;
import static java.util.function.Function.identity;
import static xtdb.util.NormalForm.datalogForm;

public class ValueVectorReader implements IVectorReader {
    private static final IFn VEC_TO_READER = Clojure.var("xtdb.vector.reader", "vec->reader");

    private static final Keyword ABSENT_KEYWORD = Keyword.intern("xtdb", "absent");

    public static IVectorReader from(ValueVector v) {
        return (IVectorReader) VEC_TO_READER.invoke(v);
    }

    private final ValueVector vector;

    public ValueVectorReader(ValueVector vector) {
        this.vector = vector;
    }

    @Override
    public int valueCount() {
        return vector.getValueCount();
    }

    @Override
    public String getName() {
        return vector.getName();
    }

    @Override
    public IVectorReader withName(String colName) {
        return new RenamedVectorReader(this, colName);
    }

    @Override
    public Field getField() {
        return vector.getField();
    }

    @Override
    public int hashCode(int idx, ArrowBufHasher hasher) {
        return vector.hashCode(idx, hasher);
    }

    private RuntimeException unsupported() {
        throw new UnsupportedOperationException(vector.getClass().getName());
    }

    @Override
    public boolean isNull(int idx) {
        return vector.isNull(idx);
    }

    @Override
    public boolean getBoolean(int idx) {
        throw unsupported();
    }

    @Override
    public byte getByte(int idx) {
        throw unsupported();
    }

    @Override
    public short getShort(int idx) {
        throw unsupported();
    }

    @Override
    public int getInt(int idx) {
        throw unsupported();
    }

    @Override
    public long getLong(int idx) {
        throw unsupported();
    }

    @Override
    public float getFloat(int idx) {
        throw unsupported();
    }

    @Override
    public double getDouble(int idx) {
        throw unsupported();
    }

    @Override
    public ByteBuffer getBytes(int idx) {
        throw unsupported();
    }

    @Override
    public ArrowBufPointer getPointer(int idx) {
        if (vector instanceof ElementAddressableVector eav) {
            return eav.getDataPointer(idx);
        } else {
            throw unsupported();
        }
    }

    @Override
    public ArrowBufPointer getPointer(int idx, ArrowBufPointer reuse) {
        if (vector instanceof ElementAddressableVector eav) {
            return eav.getDataPointer(idx, reuse);
        } else {
            throw unsupported();
        }
    }

    @Override
    public Object getObject(int idx) {
        return vector.isNull(idx) ? null : getObject0(idx);
    }

    Object getObject0(int idx) {
        return vector.getObject(idx);
    }

    @Override
    public IVectorReader structKeyReader(String colName) {
        throw unsupported();
    }

    @Override
    public Collection<String> structKeys() {
        return null;
    }

    @Override
    public IVectorReader listElementReader() {
        throw unsupported();
    }

    @Override
    public int getListStartIndex(int idx) {
        throw unsupported();
    }

    @Override
    public int getListCount(int idx) {
        throw unsupported();
    }

    @Override
    public Keyword getLeg(int idx) {
        throw unsupported();
    }

    @Override
    public List<Keyword> legs() {
        throw unsupported();
    }

    @Override
    public IVectorReader legReader(Keyword legKey) {
        throw unsupported();
    }

    @Override
    public IVectorReader copyTo(ValueVector vector) {
        this.vector.makeTransferPair(vector).splitAndTransfer(0, valueCount());
        return from(vector);
    }

    @Override
    public IVectorReader transferTo(ValueVector vector) {
        if (this.vector instanceof NullVector) return this;

        this.vector.makeTransferPair(vector).transfer();
        return from(vector);
    }

    @Override
    public IRowCopier rowCopier(IVectorWriter writer) {
        return writer.rowCopier(vector);
    }

    private class BaseValueReader implements IValueReader {
        private final IVectorPosition pos;

        public BaseValueReader(IVectorPosition pos) {
            this.pos = pos;
        }

        @Override
        public Keyword getLeg() {
            return ValueVectorReader.this.getLeg(pos.getPosition());
        }

        @Override
        public boolean isNull() {
            return ValueVectorReader.this.isNull(pos.getPosition());
        }

        @Override
        public boolean readBoolean() {
            return ValueVectorReader.this.getBoolean(pos.getPosition());
        }

        @Override
        public byte readByte() {
            return ValueVectorReader.this.getByte(pos.getPosition());
        }

        @Override
        public short readShort() {
            return ValueVectorReader.this.getShort(pos.getPosition());
        }

        @Override
        public int readInt() {
            return ValueVectorReader.this.getInt(pos.getPosition());
        }

        @Override
        public long readLong() {
            return ValueVectorReader.this.getLong(pos.getPosition());
        }

        @Override
        public float readFloat() {
            return ValueVectorReader.this.getFloat(pos.getPosition());
        }

        @Override
        public double readDouble() {
            return ValueVectorReader.this.getDouble(pos.getPosition());
        }

        @Override
        public ByteBuffer readBytes() {
            return ValueVectorReader.this.getBytes(pos.getPosition());
        }

        @Override
        public Object readObject() {
            return ValueVectorReader.this.getObject(pos.getPosition());
        }
    }

    @Override
    public IValueReader valueReader(IVectorPosition pos) {
        return new BaseValueReader(pos);
    }

    @Override
    public void close() throws Exception {
        vector.close();
    }

    @Override
    public String toString() {
        return "(ValueVectorReader {vector=%s})".formatted(vector);
    }

    public static IVectorReader nullVector(NullVector v) {
        return new ValueVectorReader(v) {
            @Override
            public int hashCode(int idx, ArrowBufHasher hasher) {
                // Until https://github.com/apache/arrow/pull/35590 is merged, Arrow 13.
                return 31;
            }
        };
    }

    public static IVectorReader absentVector(AbsentVector v) {
        return new ValueVectorReader(v) {
            @Override
            public Object getObject(int idx) {
                return ABSENT_KEYWORD;
            }

            @Override
            public int hashCode(int idx, ArrowBufHasher hasher) {
                return 33;
            }
        };
    }

    public static IVectorReader bitVector(BitVector v) {
        return new ValueVectorReader(v) {
            @Override
            public boolean getBoolean(int idx) {
                return v.get(idx) != 0;
            }

            @Override
            Object getObject0(int idx) {
                return getBoolean(idx);
            }
        };
    }

    public static IVectorReader tinyIntVector(TinyIntVector v) {
        return new ValueVectorReader(v) {
            @Override
            public byte getByte(int idx) {
                return v.get(idx);
            }
        };
    }

    public static IVectorReader smallIntVector(SmallIntVector v) {
        return new ValueVectorReader(v) {
            @Override
            public short getShort(int idx) {
                return v.get(idx);
            }
        };
    }

    public static IVectorReader intVector(IntVector v) {
        return new ValueVectorReader(v) {
            @Override
            public int getInt(int idx) {
                return v.get(idx);
            }
        };
    }

    public static IVectorReader bigIntVector(BigIntVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }
        };
    }

    public static IVectorReader float4Vector(Float4Vector v) {
        return new ValueVectorReader(v) {
            @Override
            public float getFloat(int idx) {
                return v.get(idx);
            }
        };
    }

    public static IVectorReader float8Vector(Float8Vector v) {
        return new ValueVectorReader(v) {
            @Override
            public double getDouble(int idx) {
                return v.get(idx);
            }
        };
    }

    static ByteBuffer getBytes(ElementAddressableVector v, int idx) {
        if (v.isNull(idx)) return null;
        var abp = v.getDataPointer(idx);
        return abp.getBuf().nioBuffer(abp.getOffset(), (int) abp.getLength());
    }

    public static IVectorReader varCharVector(VarCharVector v) {
        return new ValueVectorReader(v) {
            @Override
            public ByteBuffer getBytes(int idx) {
                return getBytes(v, idx);
            }

            @Override
            Object getObject0(int idx) {
                return new String(v.get(idx), StandardCharsets.UTF_8);
            }
        };
    }

    public static IVectorReader keywordVector(KeywordVector v) {
        var underlyingVec = varCharVector(v.getUnderlyingVector());

        return new ValueVectorReader(v) {
            @Override
            public ByteBuffer getBytes(int idx) {
                return underlyingVec.getBytes(idx);
            }

            @Override
            Object getObject0(int idx) {
                return Keyword.intern((String) underlyingVec.getObject(idx));
            }
        };
    }

    public static IVectorReader uriVector(UriVector v) {
        var underlyingVec = varCharVector(v.getUnderlyingVector());

        return new ValueVectorReader(v) {
            @Override
            public ByteBuffer getBytes(int idx) {
                return underlyingVec.getBytes(idx);
            }

            @Override
            Object getObject0(int idx) {
                return URI.create((String) underlyingVec.getObject(idx));
            }
        };
    }

    public static IVectorReader clojureFormVector(ClojureFormVector v) {
        var underlyingVec = varCharVector(v.getUnderlyingVector());

        return new ValueVectorReader(v) {
            private static final IFn READ_STRING = Clojure.var("clojure.core/read-string");

            @Override
            public ByteBuffer getBytes(int idx) {
                return underlyingVec.getBytes(idx);
            }

            @Override
            Object getObject0(int idx) {
                return new ClojureForm(READ_STRING.invoke(underlyingVec.getObject(idx)));
            }
        };
    }

    public static IVectorReader varBinaryVector(VarBinaryVector v) {
        return new ValueVectorReader(v) {
            @Override
            public ByteBuffer getBytes(int idx) {
                return getBytes(v, idx);
            }

            @Override
            Object getObject0(int idx) {
                return ByteBuffer.wrap(v.getObject(idx));
            }
        };
    }

    public static IVectorReader fixedSizeBinaryVector(FixedSizeBinaryVector v) {
        return new ValueVectorReader(v) {
            @Override
            public ByteBuffer getBytes(int idx) {
                return getBytes(v, idx);
            }

            @Override
            Object getObject0(int idx) {
                return ByteBuffer.wrap(v.getObject(idx));
            }
        };
    }

    public static IVectorReader uuidVector(UuidVector v) {
        return new ValueVectorReader(v) {
            @Override
            public ByteBuffer getBytes(int idx) {
                return getBytes(v.getUnderlyingVector(), idx);
            }
        };
    }

    public static IVectorReader dateDayVector(DateDayVector v) {
        return new ValueVectorReader(v) {
            @Override
            public int getInt(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                int val = v.get(idx);
                if (val == Integer.MIN_VALUE || val == Integer.MAX_VALUE) return null;
                return LocalDate.ofEpochDay(val);
            }
        };
    }

    public static IVectorReader dateMilliVector(DateMilliVector v) {
        return new ValueVectorReader(v) {
            @Override
            public int getInt(int idx) {
                return (int) (v.get(idx) / 86_400_000);
            }

            @Override
            Object getObject0(int idx) {
                int val = getInt(idx);
                if (val == Integer.MIN_VALUE || val == Integer.MAX_VALUE) return null;
                return LocalDate.ofEpochDay(val);
            }
        };
    }

    private static ZoneId zoneId(ValueVector v) {
        return ZoneId.of(((ArrowType.Timestamp) v.getField().getType()).getTimezone());
    }

    public static IVectorReader timestampVector(TimeStampVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = getLong(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return v.getObject(idx);
            }
        };
    }

    public static IVectorReader timestampSecTzVector(TimeStampSecTZVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = getLong(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return Instant.ofEpochSecond(val).atZone(zoneId(v));
            }
        };
    }

    public static IVectorReader timestampMilliTzVector(TimeStampMilliTZVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = getLong(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return Instant.ofEpochMilli(val).atZone(zoneId(v));
            }
        };
    }

    public static IVectorReader timestampMicroTzVector(TimeStampMicroTZVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = getLong(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return Instant.EPOCH.plus(val, MICROS).atZone(zoneId(v));
            }
        };
    }

    public static IVectorReader timestampNanoTzVector(TimeStampNanoTZVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = v.get(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return Instant.ofEpochSecond(0, val).atZone(zoneId(v));
            }
        };
    }

    public static IVectorReader timeSecVector(TimeSecVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                int val = v.get(idx);
                if (val == Integer.MIN_VALUE || val == Integer.MAX_VALUE) return null;
                return LocalTime.ofSecondOfDay(val);
            }
        };
    }

    public static IVectorReader timeMilliVector(TimeMilliVector v) {
        return new ValueVectorReader(v) {
            @Override
            public int getInt(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                int val = v.get(idx);
                if (val == Integer.MIN_VALUE || val == Integer.MAX_VALUE) return null;
                return LocalTime.ofNanoOfDay(val * 1_000_000L);
            }
        };
    }

    public static IVectorReader timeMicroVector(TimeMicroVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = v.get(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return LocalTime.ofNanoOfDay(val * 1_000L);
            }
        };
    }

    public static IVectorReader timeNanoVector(TimeNanoVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                long val = v.get(idx);
                if (val == Long.MIN_VALUE || val == Long.MAX_VALUE) return null;
                return LocalTime.ofNanoOfDay(val);
            }
        };
    }

    public static IVectorReader intervalYearVector(IntervalYearVector v) {
        return new ValueVectorReader(v) {
            @Override
            public int getInt(int idx) {
                return v.get(idx);
            }

            @Override
            Object getObject0(int idx) {
                return new IntervalYearMonth(Period.ofMonths(getInt(idx)));
            }

            @Override
            public IValueReader valueReader(IVectorPosition pos) {
                return new BaseValueReader(pos) {
                    @Override
                    public Object readObject() {
                        // return PeriodDuration as it's still required by the EE
                        return new PeriodDuration(v.getObject(pos.getPosition()), Duration.ZERO);
                    }
                };
            }
        };
    }

    public static IVectorReader intervalDayVector(IntervalDayVector v) {
        var holder = new NullableIntervalDayHolder();

        return new ValueVectorReader(v) {
            @Override
            IntervalDayTime getObject0(int idx) {
                v.get(idx, holder);
                return new IntervalDayTime(Period.ofDays(holder.days), Duration.ofMillis(holder.milliseconds));
            }

            @Override
            public IValueReader valueReader(IVectorPosition pos) {
                return new BaseValueReader(pos) {
                    @Override
                    public PeriodDuration readObject() {
                        // return PeriodDuration as it's still required by the EE
                        IntervalDayTime idt = getObject0(pos.getPosition());
                        return new PeriodDuration(idt.period(), idt.duration());
                    }
                };
            }
        };
    }

    public static IVectorReader intervalMdnVector(IntervalMonthDayNanoVector v) {
        var holder = new NullableIntervalMonthDayNanoHolder();

        return new ValueVectorReader(v) {
            @Override
            Object getObject0(int idx) {
                v.get(idx, holder);
                return new IntervalMonthDayNano(Period.of(0, holder.months, holder.days), Duration.ofNanos(holder.nanoseconds));
            }

            @Override
            public IValueReader valueReader(IVectorPosition pos) {
                return new BaseValueReader(pos) {
                    @Override
                    public PeriodDuration readObject() {
                        // return PeriodDuration as it's still required by the EE
                        return v.getObject(pos.getPosition());
                    }
                };
            }
        };
    }

    public static IVectorReader durationVector(DurationVector v) {
        return new ValueVectorReader(v) {
            @Override
            public long getLong(int idx) {
                return DurationVector.get(v.getDataBuffer(), idx);
            }

            @Override
            public IValueReader valueReader(IVectorPosition pos) {
                return new BaseValueReader(pos) {
                    @Override
                    public Object readObject() {
                        // return PeriodDuration as it's still required by the EE
                        return new PeriodDuration(Period.ZERO, v.getObject(pos.getPosition()));
                    }
                };
            }
        };
    }

    public static IVectorReader structVector(StructVector v) {
        var childVecs = v.getChildrenFromFields();
        var rdrs = childVecs.stream().collect(Collectors.toMap(ValueVector::getName, ValueVectorReader::from));
        var ks = rdrs.keySet().stream().collect(Collectors.toMap(identity(), name -> datalogForm(Keyword.intern(name))));

        return new ValueVectorReader(v) {
            @Override
            public Collection<String> structKeys() {
                return rdrs.keySet();
            }

            @Override
            public IVectorReader structKeyReader(String colName) {
                return rdrs.get(colName);
            }

            @Override
            Object getObject0(int idx) {
                var res = new HashMap<Keyword, Object>();

                rdrs.forEach((k, v) -> {
                    Object val = v.getObject(idx);
                    if (!ABSENT_KEYWORD.equals(val)) res.put(ks.get(k), val);
                });

                return PersistentArrayMap.create(res);
            }

            @Override
            public IValueReader valueReader(IVectorPosition pos) {
                var readers = structKeys().stream().collect(Collectors.toMap(k -> k, k -> structKeyReader(k).valueReader(pos)));
                return new BaseValueReader(pos) {
                    @Override
                    public Map<String, IValueReader> readObject() {
                        return readers;
                    }
                };
            }
        };
    }

    private static class ListVectorReader extends ValueVectorReader {
        private final ListVector v;
        private final IVectorReader elReader;

        public ListVectorReader(ListVector v) {
            super(v);
            this.v = v;
            this.elReader = from(v.getDataVector());
        }

        @Override
        Object getObject0(int idx) {
            var startIdx = getListStartIndex(idx);
            return PersistentVector.create(
                    IntStream.range(0, getListCount(idx))
                            .mapToObj(elIdx -> elReader.getObject(startIdx + elIdx))
                            .toList());
        }

        @Override
        public IVectorReader listElementReader() {
            return elReader;
        }

        @Override
        public int getListStartIndex(int idx) {
            return v.getElementStartIndex(idx);
        }

        @Override
        public int getListCount(int idx) {
            return v.getElementEndIndex(idx) - v.getElementStartIndex(idx);
        }

        @Override
        public IValueReader valueReader(IVectorPosition pos) {
            var elPos = IVectorPosition.build();
            var elValueReader = elReader.valueReader(elPos);

            return new BaseValueReader(pos) {
                @Override
                public Object readObject() {
                    var startIdx = getListStartIndex(pos.getPosition());
                    var valueCount = getListCount(pos.getPosition());

                    return new IListValueReader() {
                        @Override
                        public int size() {
                            return valueCount;
                        }

                        @Override
                        public IValueReader nth(int elIdx) {
                            elPos.setPosition(startIdx + elIdx);
                            return elValueReader;
                        }
                    };
                }
            };
        }
    }

    public static IVectorReader listVector(ListVector v) {
        return new ListVectorReader(v);
    }

    public static IVectorReader setVector(SetVector v) {
        var listReader = listVector(v.getUnderlyingVector());

        return new ValueVectorReader(v) {
            @Override
            Object getObject0(int idx) {
                return PersistentHashSet.create((List<?>) listReader.getObject(idx));
            }

            @Override
            public IVectorReader listElementReader() {
                return listReader.listElementReader();
            }

            @Override
            public int getListStartIndex(int idx) {
                return listReader.getListStartIndex(idx);
            }

            @Override
            public int getListCount(int idx) {
                return listReader.getListCount(idx);
            }

            @Override
            public IValueReader valueReader(IVectorPosition pos) {
                return listReader.valueReader(pos);
            }
        };
    }

    private record DuvIndirection(DenseUnionVector v, byte typeId) implements IVectorIndirection {
        @Override
        public int valueCount() {
            return v.getValueCount();
        }

        @Override
        public int getIndex(int idx) {
            return v.getTypeId(idx) == typeId ? v.getOffset(idx) : -1;
        }
    }

    public static class DuvReader extends ValueVectorReader {
        private final DenseUnionVector v;

        private final List<Keyword> legs;
        private final Map<Keyword, IVectorReader> legReaders = new ConcurrentHashMap<>();

        private DuvReader(DenseUnionVector v) {
            super(v);
            this.v = v;

            // only using getChildrenFromFields because DUV.getField is so expensive.
            List<? extends ValueVector> children = v.getChildrenFromFields();
            this.legs = children.stream().map(c -> Keyword.intern(c.getName())).toList();
        }

        @Override
        public boolean isNull(int idx) {
            return v.getVectorByType(getTypeId(idx)).isNull(v.getOffset(idx));
        }

        @Override
        @SuppressWarnings("resource")
        Object getObject0(int idx) {
            return legReader(getLeg(idx)).getObject(idx);
        }

        private byte getTypeId(int idx) {
            return v.getTypeId(idx);
        }

        @Override
        public Keyword getLeg(int idx) {
            return legs.get(getTypeId(idx));
        }

        @Override
        public IVectorReader legReader(Keyword legKey) {
            return legReaders.computeIfAbsent(legKey, k -> {
                var child = v.getChild(k.sym.toString());
                return new IndirectVectorReader(ValueVectorReader.from(child), new DuvIndirection(v, ((byte) legs.indexOf(k))));
            });
        }

        @Override
        public List<Keyword> legs() {
            return legs;
        }

        @Override
        public IValueReader valueReader(IVectorPosition pos) {
            var legReaders = legs.stream().collect(Collectors.toMap(l -> l, l -> DuvReader.this.legReader(l).valueReader(pos)));

            return new IValueReader() {
                @Override
                public Keyword getLeg() {
                    return DuvReader.this.getLeg(pos.getPosition());
                }

                private IValueReader legReader() {
                    return legReaders.get(getLeg());
                }

                @Override
                public boolean isNull() {
                    return legReader().isNull();
                }

                @Override
                public boolean readBoolean() {
                    return legReader().readBoolean();
                }

                @Override
                public byte readByte() {
                    return legReader().readByte();
                }

                @Override
                public short readShort() {
                    return legReader().readShort();
                }

                @Override
                public int readInt() {
                    return legReader().readInt();
                }

                @Override
                public long readLong() {
                    return legReader().readLong();
                }

                @Override
                public float readFloat() {
                    return legReader().readFloat();
                }

                @Override
                public double readDouble() {
                    return legReader().readDouble();
                }

                @Override
                public ByteBuffer readBytes() {
                    return legReader().readBytes();
                }

                @Override
                public Object readObject() {
                    return legReader().readObject();
                }
            };
        }
    }

    public static IVectorReader denseUnionVector(DenseUnionVector v) {
        return new DuvReader(v);
    }
}
