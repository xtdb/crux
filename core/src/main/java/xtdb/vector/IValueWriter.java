package xtdb.vector;

import clojure.lang.Keyword;

import java.nio.ByteBuffer;

/**
 * Interface for writing a value - likely either a {@link ValueBox} or a {@link IVectorWriter}.
 * <p>
 * To write a struct, call {@link IValueWriter#startStruct()}, write your values using {@link IValueWriter#structKeyWriter(String)}s, then call {@link IValueWriter#endStruct()}
 * To write a list, call {@link IValueWriter#startList()}, write elements on the {@link IValueWriter#listElementWriter()}s, then call {@link IValueWriter#endList()}
 * To write a polymorphic value, first get a writer for each type/type-id, then write your values to the respective child writer.
 * <p>
 * The child writers can be requested once (i.e. per batch) and then re-used for each row.
 */
public interface IValueWriter {

    void writeNull();

    void writeBoolean(boolean booleanValue);

    void writeByte(byte byteValue);

    void writeShort(short shortValue);

    void writeInt(int intValue);

    void writeLong(long longValue);

    void writeFloat(float floatValue);

    void writeDouble(double doubleValue);

    void writeBytes(ByteBuffer bytesValue);

    void writeObject(Object objectValue);

    IValueWriter structKeyWriter(String key);

    void startStruct();

    void endStruct();

    IValueWriter listElementWriter();

    void startList();

    void endList();

    IValueWriter legWriter(Keyword leg);
}
