import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class Receiver {
    private static int receiverPort;
    private static String fileName;
    private static DatagramSocket receiverSocket;
    private static InetAddress sourceAddress;
    private static int sourcePort;

    private static final int HEADER_SIZE = 9;
    private static final int ACK_FLAG = 0;
    private static final int SYN_FLAG = 1;
    private static final int FIN_FLAG = 2;

    public static void main(String[] args) throws IOException {
        // First grab the arguments from the command line ensuring that there are 2.
        if (args.length != 2) {
            System.out.println("Required arguments: receiver_port, file_r.pdf");
            return;
        } else {
            if (!bootstrapReceiver(args)) {
                System.out.println("Failed to bootstrap the receiver.");
                return;
            }
        }

        if(!handshake()) {
            System.out.println("Failed to complete handshake with client.");
            return;
        }
    }

    private static boolean handshake() throws IOException {
        // Create a Datagram Packet to store the incoming Syn Packet
        DatagramPacket synPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);

        // Block while waiting for the initial SYN Packet to arrive
        System.out.println("Blocking while waiting for Syn Packet to arrive");
        while(!checkSTPHeaderFlags(synPacket, SYN_FLAG)) {
            receiverSocket.receive(synPacket);
        }

        System.out.println("SYN successfully received");
        sourceAddress = synPacket.getAddress();
        sourcePort = synPacket.getPort();

        // TODO continue

        return true;
    }

    private static boolean bootstrapReceiver(String[] args) {
        receiverPort = Integer.parseInt(args[0]);
        fileName = args[1];

        try {
            receiverSocket = new DatagramSocket();
        } catch (SocketException e) {
            System.out.println("Failed to setup UDP socket");
            e.printStackTrace();
            return false;
        }

        return true;
    }

    /**
     * Checks the STP header, returning whether or not the desired flag has been checked.
     * @param packet
     * @param flag
     * @return
     */
    private static boolean checkSTPHeaderFlags(DatagramPacket packet, int flag) {
        STP header = getHeaderFromPacket(packet);
        return header.checkFlag(flag);
    }

    /**
     * Checks the STP header, returning whether or not the ACKNum is as desired.
     * @param packet
     * @param ackNum
     * @return
     */
    private static boolean checkSTPAckNum(DatagramPacket packet, int ackNum) {
        STP header = getHeaderFromPacket(packet);
        return header.checkAckNum(ackNum);
    }

    /**
     * Gets the STP header from a Datagram Packet
     * @param packet
     * @return
     */
    private static STP getHeaderFromPacket(DatagramPacket packet) {
        byte[] packetData = packet.getData();
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(packetData, 0, header, 0, HEADER_SIZE);
        STP stpHeader = new STP(header);
        return stpHeader;
    }
}
