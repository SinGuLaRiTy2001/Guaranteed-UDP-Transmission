import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

public class VSFtp {
    public static final short MINLEN = 4;   
    public static final short MAX_FILENAME_LEN = 128;
    public static final Integer MAX_DATA_LEN = 128;
    public static final Integer MAX_LEN = MAX_DATA_LEN + MINLEN;
    public static final short TYPE_BEGIN = 1;
    public static final short TYPE_DATA = 2;
    public static final short TYPE_END = 3; 

    private ByteBuffer byteBuffer;
    private int vsType;
    private byte[] vsData;
    private int datalength;
    
    public VSFtp(DatagramPacket packet) {
        byte[] packetData = packet.getData();
        byteBuffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
        byteBuffer.order(ByteOrder.BIG_ENDIAN);
        vsType = byteBuffer.getInt();
        if (vsType == TYPE_BEGIN || vsType == TYPE_DATA) {
            vsData = new byte[byteBuffer.remaining()];
            byteBuffer.get(vsData);
            datalength = vsData.length;
        }
    }

    private void alloc(int vstype, int vslen) {
        byteBuffer = ByteBuffer.allocate(vslen);
        byteBuffer.order(ByteOrder.BIG_ENDIAN);     
        byteBuffer.putInt(vstype);
        vsType = vstype;
    }

    public VSFtp(int vstype) {
        alloc(vstype, MINLEN);
        datalength = 0;
    }

    public VSFtp(int vstype, byte[] data, int length) {
        alloc(vstype, MINLEN + length);
        byteBuffer.put(data, 0, length);
        vsData = data;
        datalength = length;
    }

    public VSFtp(int vstype, String filename) {
        byte[] encodedName = filename.getBytes(StandardCharsets.UTF_8);
        alloc(vstype, MINLEN + encodedName.length);
        byteBuffer.put(encodedName);
        datalength = encodedName.length;
        vsData = encodedName;
    }

    public int getType() {
        return vsType;
    }

    public String getFilename() throws IOException {
        if (vsType != TYPE_BEGIN)
            throw new IOException("Not BEGIN message");
        return new String(vsData, StandardCharsets.UTF_8);
    }

    public byte[] getData() throws IOException {
        if (vsType != TYPE_DATA)
            throw new IOException("Not DATA message");
        return vsData;
    }

    public int length() {
        int len = MINLEN;
        if (vsType != TYPE_END)
            len += datalength;
        return len;
    }

    public byte[] getBytes() {
        byteBuffer.rewind();
        byte[] buf = new byte[this.length()];
        byteBuffer.get(buf, 0, length());
        return buf;
    }

    public DatagramPacket getPacket(InetSocketAddress sockaddr) {
        byte[] data = getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, sockaddr);
        return packet;
    }

    public String asString() throws IOException {
        String type;
        if (vsType == TYPE_BEGIN)
            type = "BEGIN";
        else if (vsType == TYPE_DATA)
            type = "DATA";
        else if (vsType == TYPE_END)
            type = "END";
        else
            type = "??";

        String data = "";
        if (vsType == TYPE_BEGIN)
            data = " " + getFilename();
        else if (vsType == TYPE_DATA)
            data = " <" + String.valueOf(datalength) + " bytes>";

        return type + data;
    }
}

