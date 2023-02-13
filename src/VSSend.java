import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;

class VSFtpSender implements Runnable {
    private GUDPSocket gUdpSocket;
    private ArrayList<InetSocketAddress> destSocketAddresses;
    private String[] fileNames;
    private boolean debug = false;
    
    VSFtpSender(GUDPSocket socket, ArrayList<InetSocketAddress> destinations, String[] files) {
        gUdpSocket = socket;
        destSocketAddresses = destinations;
        fileNames = files;
    }

    private void sendAll(VSFtp vsPacket) throws IOException {
        for (InetSocketAddress sockaddr: destSocketAddresses) {
            DatagramPacket datagramPacket = vsPacket.getPacket(sockaddr);
            gUdpSocket.send(datagramPacket);
        }
    }

    private void sendFile(String fileName) throws IOException {
        FileInputStream inputStream = new FileInputStream(fileName);
        VSFtp vsBegin = new VSFtp(VSFtp.TYPE_BEGIN, fileName);
        sendAll(vsBegin);
        byte[] fileBuffer = new byte[VSFtp.MAX_DATA_LEN];
        int byteRead;
        while ((byteRead = inputStream.read(fileBuffer, 0, VSFtp.MAX_DATA_LEN)) != -1) {
            VSFtp vsData = new VSFtp(VSFtp.TYPE_DATA, fileBuffer, byteRead);
            sendAll(vsData);
        }
        VSFtp vsEnd = new VSFtp(VSFtp.TYPE_END);
        sendAll(vsEnd);
        gUdpSocket.finish();
    }
    
    private boolean setDebug(boolean dbg) {
        boolean old = this.debug;
        this.debug = dbg;
        return old;
    }

    public void run() {
        try {
            for (String fileName: this.fileNames) {
                sendFile(fileName);
            }
	    gUdpSocket.close();
        } catch (Exception e) {
            System.err.println("Exception in VS sender");
            e.printStackTrace();
        }
    }
}

public class VSSend {
    static boolean debug = false;
    static ArrayList<InetSocketAddress> destSocketAddresses;
    static String[] fileNames;
    static GUDPSocket gUdpSocket;
    
    private static void usage() {
        System.err.print( "Usage: VSSend [-d] host1:port1 [host2:port2] ... file1 [file2]...\n");
        System.exit(1);

    }

    private static void getargs(String[] args) {
        int index = 0;

        if (args.length > 0 && args[index].equals("-d")) {
            debug = true;
            index++;
        }
        destSocketAddresses = new ArrayList<InetSocketAddress>();
        while (index < args.length) {
            String inetaddr = args[index];
            if (inetaddr.indexOf(':') == -1)
                break;
            String[] hostport = inetaddr.split(":", 2);
            InetSocketAddress sockaddr = new InetSocketAddress(hostport[0], Integer.parseInt(hostport[1]));
            destSocketAddresses.add(sockaddr);
            index++;
        }
        fileNames = Arrays.copyOfRange(args, index, args.length);
        for (String s: fileNames)
        if (destSocketAddresses.size() == 0 || fileNames.length == 0)
            usage();
    }


    public static void main(String[] args) throws IOException {
        getargs(args);
        DatagramSocket dsock = new DatagramSocket();
        gUdpSocket = new GUDPSocket(dsock);

        VSFtpSender vsSender = new VSFtpSender(gUdpSocket, destSocketAddresses, fileNames);
        Thread sender = new Thread(vsSender, "VSFTP Sender");
        sender.start();
    }
}
