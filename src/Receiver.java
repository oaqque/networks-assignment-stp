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
    private static int currentSeqNum;
    private static int currentAckNum;

    private static final int HEADER_SIZE = 9;
    private static final int ACK_FLAG = 0;
    private static final int SYN_FLAG = 1;
    private static final int FIN_FLAG = 2;

    public static void main (String[] args) throws IOException {
        // First grab the arguments from the command line ensuring that there are 2
        if (args.length != 2) {
            System.out.println("Required arguments: receiver_port, file_r.pdf");
            return;
        } else {
            // If there are no issues with the arguments provided in the command line then bootstrap the Receiver
            // server
            if (!bootstrapReceiver(args)) {
                System.out.println("Failed to bootstrap the receiver.");
                return;
            }
        }

        // Start the three-way handshake process with the source
        if (!handshake()) {
            System.out.println("Failed to complete handshake with client.");
            return;
        }

        DatagramPacket dataPacket = new DatagramPacket(new byte[1024], 1024); // TODO comment on this
        STP packetSTP;
        byte[] packetHeader;
        int segmentSize;

        // After handshake has been completed, keep the server waiting for packets
        while (true) {

            System.out.println("Block while waiting for data packet...");
            receiverSocket.receive(dataPacket);
            packetSTP = getHeaderFromPacket(dataPacket);
            packetHeader = packetSTP.getHeader();

            // After receiving the packet ensure that this is not the final packet. If it is then we break the loop
            // of receiving packets and initiate shutdown.
            if (checkSTPHeaderFlags(dataPacket, FIN_FLAG)) {
                // TODO
                break;
            }
            System.out.println("Packet is not FIN Packet...");

            // Check if the packets are out of order...
            if (currentAckNum != packetSTP.getSequenceNum()) {
                // TODO
            }
            System.out.println("Packet is in order...");

            // Since it is not a FIN Packet then we simply ACK the packet.
            // First get the sequence number from the packet we just received and ACK back the sequence number + the
            // number of bytes received. The number of bytes sent is the length of the UDP datapacket - the length of
            // the STP header.

            segmentSize = dataPacket.getLength() - packetHeader.length;
            currentAckNum += segmentSize;

            System.out.println("Creating ACK Packet...");
            STP ackSegment = new STP(true, false, false, currentSeqNum, currentAckNum);
            DatagramPacket ackPacket = new DatagramPacket(ackSegment.getHeader(), ackSegment.getHeader().length,
                    sourceAddress, sourcePort);

            receiverSocket.send(ackPacket);
            System.out.println("ACK Packet successfully sent. Ack Num: " + currentAckNum);
        }

    }

    private static boolean bootstrapReceiver (String[] args) {
        receiverPort = Integer.parseInt(args[0]);
        fileName = args[1];

        try {
            receiverSocket = new DatagramSocket(receiverPort);
        } catch (SocketException e) {
            System.out.println("Failed to setup UDP socket");
            e.printStackTrace();
            return false;
        }

        return true;
    }

    private static boolean handshake () throws IOException {
        // Create a Datagram Packet to store the incoming Syn Packet.
        DatagramPacket synPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);

        // Block while waiting for the initial SYN Packet to arrive.
        System.out.println("Block while waiting for SYN Packet to arrive...");
        while (!checkSTPHeaderFlags(synPacket, SYN_FLAG)) {
            receiverSocket.receive(synPacket);
        }

        // Once you have accepted the original SYN Packet, note down the address and port number of the source in
        // order to send ACK packets back. Note down the ISN in order to ACK the correct packet.
        System.out.println("SYN successfully received");
        sourceAddress = synPacket.getAddress();
        sourcePort = synPacket.getPort();
        STP synSegment = getHeaderFromPacket(synPacket);
        int sourceISN = synSegment.getSequenceNum();
        int receiverISN = 0;

        // Create a SYNACK Packet and send it back to the host.
        System.out.println("Creating SYNACK Packet...");
        STP synAckSegment = new STP(true, true, false, receiverISN, sourceISN+1);
        DatagramPacket synAckPacket = new DatagramPacket(synAckSegment.getHeader(), synAckSegment.getHeader().length,
                sourceAddress, sourcePort);
        receiverSocket.send(synAckPacket);
        System.out.println("SYNACK Packet successfully sent");

        // Create a Datagram Packet to store the incoming Ack Packet
        DatagramPacket ackPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);

        // Block while waiting for final ACK Packet to signal that the three-way handshake is complete.
        System.out.println("Block while waiting for final ACK Packet to arrive...");
        while (!checkSTPHeaderFlags(ackPacket, ACK_FLAG) && !checkSTPAckNum(ackPacket, receiverISN+1)) {
            receiverSocket.receive(ackPacket);
        }
        currentSeqNum = receiverISN+1;
        System.out.println("ACK successfully received, three way handshake complete");

        return true;
    }

    /**
     * Checks the STP header, returning whether or not the desired flag has been checked.
     * @param packet
     * @param flag
     * @return
     */
    private static boolean checkSTPHeaderFlags (DatagramPacket packet, int flag) {
        STP header = getHeaderFromPacket(packet);
        return header.checkFlag(flag);
    }

    /**
     * Checks the STP header, returning whether or not the ACKNum is as desired.
     * @param packet
     * @param ackNum
     * @return
     */
    private static boolean checkSTPAckNum (DatagramPacket packet, int ackNum) {
        STP header = getHeaderFromPacket(packet);
        return header.checkAckNum(ackNum);
    }

    /**
     * Gets the STP header from a Datagram Packet
     * @param packet
     * @return
     */
    private static STP getHeaderFromPacket (DatagramPacket packet) {
        byte[] packetData = packet.getData();
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(packetData, 0, header, 0, HEADER_SIZE);
        STP stpHeader = new STP(header);
        return stpHeader;
    }
}
