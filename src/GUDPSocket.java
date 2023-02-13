import java.io.IOException;

import java.net.*;

import java.util.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.*;

import java.time.Duration;
import java.time.Instant;

public class GUDPSocket implements GUDPSocketAPI {

    DatagramSocket datagramSocket;

    ReceiveThread receive ;
    Thread receiver;

    SendThread send ;
    Thread sender ;

    boolean isNewFile;

    public List<Integer> ACK_Buffer = new ArrayList<>();

    public GUDPSocket(DatagramSocket socket) throws IOException {
        //initial
        this.receive=new ReceiveThread(ACK_Buffer);
        this.receiver=new Thread(receive,"Receiver");
        this.send=new SendThread();
        this.sender=new Thread(send,"Sender");
        this.isNewFile=true;
        this.datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPPacket gudppacket = null;
        if (packet != null) {
            gudppacket = GUDPPacket.encapsulate(packet);
        }

        SocketAddress socketAddress=packet.getSocketAddress();
        send.address = (InetSocketAddress) socketAddress;
        gudppacket.setSeqno(send.SeqNum++);
        send.SendingQueue.add(new PacketBuf(gudppacket));
    }

    public void receive(DatagramPacket packet) throws IOException {
        if ( isNewFile == true ) {
            System.out.println("[Receiver] Start receiving.");
            receiver.start();
            isNewFile = false;
        }

        //receive packets
        try {
            GUDPPacket gudpPacket = receive.ReceiveQueue.take();
            if ( gudpPacket!=null ) {
                gudpPacket.decapsulate(packet);
            }

            // Detect end of file by check FTP files
            receive.FtpPacket = new VSFtp(packet);
            if ( receive.FtpPacket.getType() == VSFtp.TYPE_END ) {
                System.out.println("[Receiver] Transmission completed.");
                receive.stop();
            }
        } catch (InterruptedException e) { throw new IOException(e); }
        catch (Exception e){ throw new RuntimeException(e); }
    }

    public void finish() throws IOException {
        sender.start();
        isNewFile = true;
        System.out.println("[Sender] Start sending.");
    }

    public void close() throws IOException {
        isNewFile = true;
    }

    public void SetPacket(GUDPPacket packet, short type, short version, int Seqno, int payloadlen) throws IOException {
        packet.setType(type);
        packet.setVersion(version);
        packet.setSeqno(Seqno);
        packet.setPayloadLength(payloadlen);
    }

    class SendThread implements Runnable {
        final List<PacketBuf> SendingQueue = new ArrayList<>(); // Priority Queue: Packets sorting
        final List<Integer> ACKBuffer = new ArrayList<>();
        InetSocketAddress address;

        final int WindowSize = GUDPPacket.MAX_WINDOW_SIZE;
        final int MAXIMUN_RESEND = 10;
        int WindowSize_Current;
        int SendBase;
        int SendEnd;

        int BSNNum;
        int SeqNum;
        boolean isComplete;
        int Packet_cnt;
        int ACK_index;

        public SendThread() throws IOException {
            System.out.println("[Sender] Send thread started.");

            WindowSize_Current = WindowSize;
            SendBase = 0;
            SendEnd = SendBase + WindowSize_Current;
            BSNNum = new Random().nextInt(Short.MAX_VALUE);
            SeqNum = BSNNum;
            isComplete = false;
            Packet_cnt = 0;
            ACK_index = 0;

            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);

            GUDPPacket packet = new GUDPPacket(buffer);
            SetPacket(packet, GUDPPacket.TYPE_BSN, GUDPPacket.GUDP_VERSION, SeqNum, 0);
            SeqNum++;

            SendingQueue.add(new PacketBuf(packet));
        }

        public void run() {
            ReceiveThread ACK;
            ACK = new ReceiveThread(ACKBuffer);

            Thread ACK_Receiver = new Thread(ACK, "ACK Receiver");
            ACK_Receiver.start();
            System.out.println("[ACK_Receiver] ACK receive thread started.");

            // try {
            //     send(SendingQueue.get(0));// Send BSN
            //     System.out.println("[Sender] BSN sent:"
            //             + BSNNum );
            // } catch (IOException e) { throw new RuntimeException(e); }

            while (!isComplete) {
                try {
                    // Slide_Window(ACKBuffer);
                    // Slide Window
                    for (int i = ACK_index; i < ACKBuffer.size(); i++) {
                        PacketBuf Packet_Queue = SendingQueue.get(ACKBuffer.get(i) - BSNNum - 1);
                        if (Packet_Queue.isACKRcv == false) {
                            this.Packet_cnt++;
                            // this.WindowSize_Current = WindowSize;
                            Packet_Queue.isACKRcv = true;
                        }
                        this.ACK_index++;
                    }

                    int SendIndex;
                    for (SendIndex = SendBase; SendIndex < SendEnd; SendIndex++) {
                        if (SendingQueue.get(SendIndex).isACKRcv == true) {
                            this.SendBase++;
                            // if (SendBase == 1) {
                            // System.out.println("[Sender] Packets in air.");
                            // }
                        }
                        else {
                            break;
                        }
                    }

                    if (Packet_cnt != SendingQueue.size()) {
                        int SendEnd_new = Math.min(SendingQueue.size(),
                                SendBase + WindowSize_Current);
                        SendIndex = SendBase;
                        while (SendIndex < SendEnd_new) {
                            PacketBuf packet = SendingQueue.get(SendIndex);
                            if (packet.isSent == false) {
                                //Send packet
                                packet.isSent = true;
                                packet.resendTime = Instant.now().plus(packet.TIMEOUT).minusNanos(1);
                                packet.packet.setSocketAddress(address);
                                datagramSocket.send(packet.packet.pack());
                            }
                            SendIndex++;
                        }
                        SendEnd = SendEnd_new;
                    }

                    if (Packet_cnt == SendingQueue.size()) {
                        isComplete = true;
                        System.out.println("[Sender] Transmission completed.");
                        System.out.println("[Sender] Send thread terminated.");
                        stop();
                    }

                    //Timer
                    SendIndex = SendBase;
                    while ( SendIndex < SendEnd ) {
                        PacketBuf Packet_Queue = SendingQueue.get(SendIndex);
                        // System.out.println("[Sender] Sending Packets:" + (i-SendBase+1) + "/" + (SendEnd-SendBase+1));

                        boolean TimeoutFlag;
                        if ( Packet_Queue.isACKRcv == false && Packet_Queue.isSent == true && Instant.now().isAfter(Packet_Queue.resendTime) == true )
                            TimeoutFlag = true;
                        else
                            TimeoutFlag = false;

                        if (TimeoutFlag == true && Packet_Queue.Retrans_cnt < MAXIMUN_RESEND) {
                            // Send packet in queue
                            Packet_Queue.isSent = true;
                            Packet_Queue.resendTime = Instant.now().plus(Packet_Queue.TIMEOUT).minusNanos(1);
                            Packet_Queue.packet.setSocketAddress(address);
                            datagramSocket.send(Packet_Queue.packet.pack());

                            System.out.println("[Sender] Retransmission:"
                                    + (Packet_Queue.Retrans_cnt + 1)
                                    + "/"
                                    + MAXIMUN_RESEND);
                            Packet_Queue.Retrans_cnt++;
                        }
                        if (Packet_Queue.Retrans_cnt != MAXIMUN_RESEND) {
                            SendIndex++;
                            continue;
                        }
                        else {
                            isComplete = true;
                            System.out.println("[Sender] Transmission failed.");
                            System.out.println("[Sender] Send thread terminated.");
                            this.stop();
                            SendIndex++;
                        }
                    }

                } catch (IOException e) { throw new RuntimeException(e); }
            }
            ACK.stop();
        }

        public void stop() {
            System.exit(0);
        }
    }

    class ReceiveThread implements Runnable {
        // Priority Queue: Sort packets
        private Queue<GUDPPacket> ReceiveBuffer_Sort;
        private BlockingQueue<GUDPPacket> ReceiveQueue;

        private int ExpSeqNum = 0;
        private boolean flag = true;

        List<Integer> ACKBuffer_Recv;

        VSFtp FtpPacket;

        //constructor
        public ReceiveThread(List<Integer> ackbuffer) {
            System.out.println("[Receiver] Receive thread started.");

            this.ReceiveBuffer_Sort = new PriorityQueue<>(Comparator.comparingInt(GUDPPacket::getSeqno));
            this.ReceiveQueue = new LinkedBlockingQueue<>();

            this.ACKBuffer_Recv = ackbuffer;
        }

        private void sendACK(GUDPPacket Packet) throws IOException {
            int ACK_Num = Packet.getSeqno()+1;

            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);

            GUDPPacket packet = new GUDPPacket(buffer);
            SetPacket(packet, GUDPPacket.TYPE_ACK, GUDPPacket.GUDP_VERSION, ACK_Num, 0);
            packet.setSocketAddress(Packet.getSocketAddress());

            datagramSocket.send(packet.pack());
            System.out.println("[Receiver] ACK sent: "
                    + ACK_Num);
        }

        public void run() {
            while (this.flag) {
                try {
                    byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                    DatagramPacket udpPacket = new DatagramPacket(buf, buf.length);
                    datagramSocket.receive(udpPacket);
                    GUDPPacket gudppacket = GUDPPacket.unpack(udpPacket);
                    if (gudppacket.getType() == GUDPPacket.TYPE_BSN) {
                        ReceiveBuffer_Sort.add(gudppacket);
                        ExpSeqNum = gudppacket.getSeqno() + 1;
                        sendACK(gudppacket);
                    } else if (gudppacket.getType() == GUDPPacket.TYPE_DATA) {
                        if (gudppacket.getSeqno() == ExpSeqNum) { // Send ACK only when arriving packets number = ExpSeqNum
                            ReceiveBuffer_Sort.add(gudppacket);
                            sendACK(gudppacket);
                        }
                    } else if (gudppacket.getType() == GUDPPacket.TYPE_ACK) {
                        ACKBuffer_Recv.add(gudppacket.getSeqno());
                    }
                } catch (IOException e) { throw new RuntimeException(e); }

                while (true) {
                    GUDPPacket packet = ReceiveBuffer_Sort.peek();
                    if (Objects.isNull(packet)) {
                        break;
                    }
                    if (packet.getSeqno() >= ExpSeqNum) {
                        ExpSeqNum++;
                        ReceiveBuffer_Sort.remove();
                        ReceiveQueue.add(packet);
                    } else {
                        ReceiveBuffer_Sort.remove();
                    }
                }
            }
        }

        public void stop() {
            this.flag = false;
            System.out.println("[Receiver] Receive thread terminated.");
            System.exit(0);
        }
    }

    class PacketBuf {
        final GUDPPacket packet;
        Instant resendTime;
        boolean isSent;
        boolean isACKRcv;
        int Retrans_cnt;

        final Duration TIMEOUT;

        public PacketBuf(GUDPPacket packet) {
            this.packet = packet;
            isSent = false;
            Retrans_cnt = 0;
            this.TIMEOUT = Duration.ofMillis(1000);
        }
    }
}
