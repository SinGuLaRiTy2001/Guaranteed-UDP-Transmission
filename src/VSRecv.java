import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;

class ReceiveContext {
    private InetSocketAddress sockaddr;
    private enum State {NONE, OPEN, CLOSED};
    private State state;
    private OutputStream outputStream;
    private boolean overwrite = false;
    private boolean debug = false;
    
    ReceiveContext(InetSocketAddress sa) {
        sockaddr = sa;
        state = State.NONE;
    }

    public boolean hasSocketAddress(InetSocketAddress sa) {
        return sockaddr.equals(sa);
    }
    public void setState(State newstate) {
        state = newstate;
    }
    public State getState() {
        return state;
    }

    public void setDebug(boolean dbg) {
        debug = dbg;
    }

    public void setOverwrite(boolean ow) {
        overwrite = ow;
    }

    private String getLocalFilename(String filename) {
        String localname = filename;
        File file = new File(localname);

        if (!overwrite) {
            Integer version = 1; 
            while (file.exists()) {
                localname = filename + '-' + version.toString();
                file = new File(localname);
                version += 1;
            }
        }
        return localname;
    }
    
    public void processPacket(DatagramPacket packet) throws IOException {
        VSFtp vspacket = new VSFtp(packet);

        if (debug) {
            InetSocketAddress sockaddr = (InetSocketAddress) packet.getSocketAddress();
            System.out.printf("From %s (%d bytes): VS %s\n",
                              sockaddr.toString(), packet.getLength(), vspacket.asString());
        }

        if (vspacket.getType() == VSFtp.TYPE_BEGIN) {
            if ((state == State.NONE) || (state == State.CLOSED)) {
                String filename = vspacket.getFilename();
                String localname = getLocalFilename(filename);
                outputStream = new FileOutputStream(localname);
                state = State.OPEN;
            }
            else
                throw new IOException("VS receiver already active"); 
        }
        else if (vspacket.getType() == VSFtp.TYPE_DATA) {
            if (state == State.OPEN) {
                byte[] data = vspacket.getData();
                outputStream.write(data);
            }
            else
                throw new IOException("VS receiver not active"); 
        }
        else if (vspacket.getType() == VSFtp.TYPE_END) {
            if (state == State.OPEN) {
                outputStream.close();
                state = State.CLOSED;
            }
            else
                throw new IOException("VS receiver not active"); 
        }
		else {
			throw new IOException(String.format("Invalid VS type %d", vspacket.getType()));
		}
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }
}

class VSFtpReceiver implements Runnable {
    private GUDPSocket gUdpSocket;
    private ArrayList<ReceiveContext> receiveContexts = new ArrayList<ReceiveContext>();
    private String[] fileNames;
    private boolean debug = false;
    private boolean overwrite = false;
    
    VSFtpReceiver(GUDPSocket socket) {
        gUdpSocket = socket;
    }

    public ReceiveContext getContext(DatagramPacket packet) {
        InetSocketAddress sockaddr = (InetSocketAddress) packet.getSocketAddress();
        for (ReceiveContext context: receiveContexts) {
            if (context.hasSocketAddress(sockaddr))
                return context;
        }
        /* Not found */
        ReceiveContext context = new ReceiveContext(sockaddr);
        context.setDebug(debug);
        context.setOverwrite(overwrite);
        receiveContexts.add(context);
        return context;
    }

    public boolean setDebug(boolean dbg) {
        boolean old = this.debug;
        this.debug = dbg;
        return old;
    }

    public boolean setOverwrite(boolean ow) {
        boolean old = this.overwrite;
        this.overwrite = ow;
        return old;
    }

    public void run() {
        while (true) {
            try {
                byte[] buf = new byte[VSFtp.MAX_LEN];
                DatagramPacket packet = new DatagramPacket(buf, VSFtp.MAX_LEN);
                gUdpSocket.receive(packet);
                ReceiveContext context = getContext(packet);
                context.processPacket(packet);
            } catch (Exception e) {
                System.err.println("Exception in VS receiver");
                e.printStackTrace();
            }
        }
    }
}

public class VSRecv {
    static boolean debug_flag = false;
    static boolean overwrite_flag = false;
    static int port;
    static GUDPSocket gUdpSocket;
    
    private static void usage() {
        System.err.print( "Usage: VSRecv [-d] [-o] port\n");
        System.exit(1);
    }

    private static void getargs(String[] args) {
        int index = 0;

        while (args.length > 0 && args[index].startsWith("-")) {
            if (args[index].equals("-d")) {
                debug_flag = true;
            }
            else if (args[index].equals("-o")) {
                overwrite_flag = true;
            }
            else
                usage();
            index++;
        }

        if (index != args.length-1)
            usage();
        
        port = Integer.parseInt(args[index]);
    }

    public static void main(String[] args) throws IOException {
        getargs(args);
        DatagramSocket dsock = new DatagramSocket(port);
        gUdpSocket = new GUDPSocket(dsock);

        VSFtpReceiver vsReceiver = new VSFtpReceiver(gUdpSocket);
        vsReceiver.setOverwrite(overwrite_flag); 
        vsReceiver.setDebug(debug_flag);
        Thread receiver = new Thread(vsReceiver, "VSFTP Receiver");
        receiver.start();
    }
}
