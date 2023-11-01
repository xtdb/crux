package xtdb.vector;

import clojure.lang.Keyword;

import java.nio.ByteBuffer;

public class ValueBox implements IValueWriter, IValueReader {
    private static final Keyword NULL_LEG = Keyword.intern("null");

    private Keyword leg;

    private long prim;
    private Object obj;

    @Override
    public Keyword getLeg() {
        return leg;
    }

    @Override
    public boolean isNull() {
        return leg == NULL_LEG;
    }

    @Override
    public boolean readBoolean() {
        return prim != 0;
    }

    @Override
    public byte readByte() {
        return (byte) prim;
    }

    @Override
    public short readShort() {
        return (short) prim;
    }

    @Override
    public int readInt() {
        return (int) prim;
    }

    @Override
    public long readLong() {
        return prim;
    }

    @Override
    public float readFloat() {
        return (float) readDouble();
    }

    @Override
    public double readDouble() {
        return Double.longBitsToDouble(prim);
    }

    @Override
    public ByteBuffer readBytes() {
        return ((ByteBuffer) obj);
    }

    @Override
    public Object readObject() {
        return obj;
    }

    @Override
    public void writeNull() {
        obj = null;
    }

    @Override
    public void writeBoolean(boolean booleanValue) {
        this.prim = booleanValue ? 1 : 0;
    }

    @Override
    public void writeByte(byte byteValue) {
        this.prim = byteValue;
    }

    @Override
    public void writeShort(short shortValue) {
        this.prim = shortValue;
    }

    @Override
    public void writeInt(int intValue) {
        this.prim = intValue;
    }

    @Override
    public void writeLong(long longValue) {
        this.prim = longValue;
    }

    @Override
    public void writeFloat(float floatValue) {
        writeDouble(floatValue);
    }

    @Override
    public void writeDouble(double doubleValue) {
        this.prim = Double.doubleToLongBits(doubleValue);
    }

    @Override
    public void writeBytes(ByteBuffer bytesValue) {
        this.obj = bytesValue;
    }

    @Override
    public void writeObject(Object objectValue) {
        this.obj = objectValue;
    }

    @Override
    public IValueWriter legWriter(Keyword leg) {
        return new BoxWriter() {
            @Override
            IValueWriter box() {
                ValueBox.this.leg = leg;
                return ValueBox.this;
            }
        };
    }

}
