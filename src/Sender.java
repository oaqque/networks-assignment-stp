import javax.xml.crypto.Data;
import java.io.*;
import java.net.*;
import java.util.Random;

public class Sender {
    private static InetAddress receiverHost;    // receiver_host_ip: The IP address of Receiver machine
    private static int receiverPort;            // receiver_port: The Port number of Receiver
    private static String fileName;             // file.pdf: The name of the pdf file
    private static int mws;                     // MWS: The maximum window size
    private static int mss;                     // MSS: The maximum segment size
    private static int gamma;                   // Used for calculation of timeout values
    private static double pDrop;                // Probability that segment is dropped
    private static double pDuplicate;           // Probability that segment not dropped is duped
    private static double pCorrupt;             // Probability that segment not dropped/duped is corrupted
    private static double pOrder;               // Probability that segment not dropped/duped/corrupted is reordered
    private static int maxOrder;                // Maximum number of packets that can be held for reordering (1-6)
    private static double pDelay;               // Probability that segment not dropped/dup/corpt/reordered is delayed
    private static int maxDelay;                // The maximum delay in ms experienced by segments that are delayed
    private static long seed;                   // The seed used for random number generator

    private static Random randomGenerator;      // The Random Number generator
    private static DatagramSocket senderSocket; // The UDP socket for the sender to send through
    private static InputStream inputReader;     // The reader for segmenting the data in the pdf
    private static File file;                   // The PDF file that is to be sent to the server
    private static int currentSeqNum;           // The current sequence number which we are up to sending
    private static int currentAckNum;           // The current acknowledgement number that the server has given us
    private static int dataSent;                // The amount of bytes that have been sent

    private static final int HEADER_SIZE = 9;
    private static final int ACK_FLAG = 0;
    private static final int SYN_FLAG = 1;
    private static final int FIN_FLAG = 2;

    public static void main(String[] args) throws IOException {
        // Get the arguments from the command line
        if (args.length != 14) {
            System.out.println("Required arguments: receiver_host_ip receiver_port file.pdf MWS MSS gamma pDrop " +
                    "pDuplicate pCorrupt pOrder maxOrder pDelay maxDelay seed");
            return;
        } else {
            if (!bootstrapSender(args)) {
                System.out.println("Failed to bootstrap the sender.");
                return;
            }
        }

        // Complete 3 way handshake
        if(!handshake()) {
            System.out.println("Failed to complete handshake with server.");
            return;
        }

        // Stop and Wait Protocol
        while (true) {
            if (file.length() >= dataSent) {
                // Create a byte array that will be attached to the UDP Packet to be sent
                byte[] udpData = new byte[mss + HEADER_SIZE];

                // Create an STP header and append it to the top of the UDP Data
                STP stp = new STP(false, false, false, currentSeqNum, currentAckNum);
                System.arraycopy(stp.getHeader(), 0, udpData, 0, HEADER_SIZE);

                // Grab the data from the input reader and add it to byte array with an offset for the header. Then
                // attach the data to a packet and send it.
                inputReader.read(udpData, HEADER_SIZE, mss);
                DatagramPacket dataPacket = new DatagramPacket(udpData, udpData.length, receiverHost, receiverPort);
                senderSocket.send(dataPacket);

                // Update the book keeping
                currentSeqNum += udpData.length - HEADER_SIZE;
                dataSent += udpData.length - HEADER_SIZE;
                System.out.println("Packet successfully sent! Data Sent: " + dataSent);
            } else {
                break;
            }
        }
        // Close connection
    }

    /**
     * Ensures that arguments are correctly parsed to the sender and in the correct format. Also sets up the random
     * number generator and sender UDP socket.
     * @param args
     * @return
     */
    private static boolean bootstrapSender (String[] args) throws FileNotFoundException {
        try {
            receiverHost = InetAddress.getByName(args[0]);
        } catch (UnknownHostException e) {
            System.out.println("Failed to retrieve receiver_host_ip");
            e.printStackTrace();
            return false;
        }

        receiverPort = Integer.parseInt(args[1]);
        fileName = args[2];
        mws = Integer.parseInt(args[3]);
        mss = Integer.parseInt(args[4]);
        gamma = Integer.parseInt(args[5]);
        pDrop = Double.parseDouble(args[6]);
        pDuplicate = Double.parseDouble(args[7]);
        pCorrupt = Double.parseDouble(args[8]);
        pOrder = Double.parseDouble(args[9]);
        maxOrder = Integer.parseInt(args[10]);
        pDelay = Double.parseDouble(args[11]);
        maxDelay = Integer.parseInt(args[12]);
        seed = Integer.parseInt(args[13]);

        randomGenerator = new Random(seed);

        try {
            senderSocket = new DatagramSocket();
        } catch (SocketException e) {
            System.out.println("Failed to setup UDP socket");
            e.printStackTrace();
            return false;
        }

        file = new File(fileName);
        inputReader = new FileInputStream(file);
        dataSent = 0;

        return true;
    }

    private static boolean handshake() throws IOException {
        // Generate a random initial sequence number for security reasons. The random number generator generates 32
        // bit values
        int clientisn = randomGenerator.nextInt();

        // Create Syn Packet and then sending it to the receiver
        System.out.println("Creating SYN Packet...");
        STP connectionRequest = new STP(false, true, false, clientisn, 0);
        DatagramPacket synPacket = new DatagramPacket(connectionRequest.getHeader(), HEADER_SIZE, receiverHost,
                receiverPort);
        senderSocket.send(synPacket);
        System.out.println("SYN Packet successfully sent");

        // Block while waiting for SYNACK Packet
        System.out.println("Block while waiting for SYNACK Packet...");
        DatagramPacket synAckPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);

        // Ensure that the packet received is a SYNACK packet, we do this by ensuring that the SYN and ACK flags are
        // both set. We also ensure that the Acknowledgement Number is equal to our initial sequence number + 1.
        // This process blocks until the SYNACK segment is correctly received.
        while (!checkSTPHeaderFlags(synAckPacket, SYN_FLAG) && !checkSTPHeaderFlags(synAckPacket, ACK_FLAG) &&
                !checkSTPAckNum(synAckPacket, clientisn+1)) {
            senderSocket.receive(synAckPacket);
        }
        System.out.println("SYNACK Packet successfully received");

        // Retrieve the STP header from the SYNACK Packet
        STP synAckSTP = getHeaderFromPacket(synAckPacket);
        int serverisn = synAckSTP.getSequenceNum();

        // Sending out the Ack for the SYNACK segment
        System.out.println("Creating ACK Packet...");
        STP ackSTP = new STP(true, false, false, clientisn+1, serverisn+1);
        DatagramPacket ackPacket = new DatagramPacket(ackSTP.getHeader(), HEADER_SIZE, receiverHost, receiverPort);
        senderSocket.send(ackPacket);
        System.out.println("ACK Packet sent, three-way handshake complete");

        // Store the correct sequence numbers and acknowledgement numbers
        currentAckNum = serverisn + 1;
        currentSeqNum = clientisn + 1;

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
