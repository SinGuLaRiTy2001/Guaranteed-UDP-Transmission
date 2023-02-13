import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;

public class GUDPPacket {
    public static final short GUDP_VERSION = 1; 
    public static final short HEADER_SIZE = 8;
    public static final Integer MAX_DATA_LEN = 1000;
    public static final Integer MAX_DATAGRAM_LEN = MAX_DATA_LEN + HEADER_SIZE;  
    public static final Integer MAX_WINDOW_SIZE = 3;
    public static final short TYPE_DATA = 1;
    public static final short TYPE_BSN = 2;
    public static final short TYPE_ACK = 3; 

    private InetSocketAddress sockaddr;
    private ByteBuffer byteBuffer;
    private Integer payloadLength;

    /* 
     * Application send processing: Build a DATA GUDP packet to encaspulate payload
     * from the application. The application payload is in the form of a DatagramPacket,
     * containing data and socket address.
     */

    public static GUDPPacket encapsulate(DatagramPacket packet) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(packet.getLength() + HEADER_SIZE);
        buffer.order(ByteOrder.BIG_ENDIAN);
        GUDPPacket gudppacket = new GUDPPacket(buffer);
        gudppacket.setType(TYPE_DATA);
        gudppacket.setVersion(GUDP_VERSION);
        byte[] data = packet.getData();
        gudppacket.setPayload(data);
        gudppacket.setSocketAddress((InetSocketAddress) packet.getSocketAddress());
        return gudppacket;
    }

    /* 
     * Application receive processing: Extract application payload into a DatagramPacket, 
     * with data and socket address.
     */
    public void decapsulate(DatagramPacket packet) throws IOException {
        int plength  = getPayloadLength();
        getPayload(packet.getData(), plength);
        packet.setLength(plength);
        packet.setSocketAddress(getSocketAddress());
    }

    /*
     * Input processing: Turn a DatagramPacket received from UDP into a GUDP packet
     */
    public static GUDPPacket unpack(DatagramPacket packet) throws IOException {
        int plength = packet.getLength();
        if (plength < HEADER_SIZE)
            throw new IOException(String.format("Too short GUDP packet: %d bytes", plength));

        byte[] data = packet.getData();
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, plength);
        buffer.order(ByteOrder.BIG_ENDIAN);
        GUDPPacket gudppacket = new GUDPPacket(buffer);
        gudppacket.setPayloadLength(plength - HEADER_SIZE);
        gudppacket.setSocketAddress((InetSocketAddress) packet.getSocketAddress());
        return gudppacket;
    }

    /*
     * Output processing: Turn headers and payload into a DatagramPacket, for sending with UDP
     */

    public DatagramPacket pack() throws IOException {
        int totlength = HEADER_SIZE + getPayloadLength();
        InetSocketAddress socketAddress = getSocketAddress();
        return new DatagramPacket(getBytes(), totlength, sockaddr);
    }
    
    /*
     * Constructor: create a GUDP packet with a ByteBuffer as back storage
     */
    public GUDPPacket(ByteBuffer buffer) {
        byteBuffer = buffer;
    }

    /* 
     * Serialization: Return packet as a byte array
     */
    public byte[] getBytes() {
        return byteBuffer.array();
    }

    public short getVersion() {
        return byteBuffer.getShort(0);
    }

    public short getType() {
        return byteBuffer.getShort(2);
    }

    public int getSeqno() {
        return byteBuffer.getInt(4);
    }

    public InetSocketAddress getSocketAddress() {
        return sockaddr;
    }

    public void setVersion(short version) {
        byteBuffer.putShort(0, version);
    }

    public void setType(short type) {
        byteBuffer.putShort(2, type);
    }

    public void setSeqno(int length) {
        byteBuffer.putInt(4, length);
    }

    public void setPayload(byte[] pload) {
        byteBuffer.position(HEADER_SIZE);
        byteBuffer.put(pload, 0, pload.length);
        payloadLength = pload.length;
    }

    public void setSocketAddress(InetSocketAddress socketAddress) {
        sockaddr = socketAddress;
    }

    public void setPayloadLength(int length) {
        payloadLength = length;
    }

    public int getPayloadLength() {
        return payloadLength;
    }

    public void getPayload(byte[] dst, int length) {
        byteBuffer.position(HEADER_SIZE);
        byteBuffer.get(dst, 0, length);
    }
}
