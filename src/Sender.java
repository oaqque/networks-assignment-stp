import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

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
    private static long maxDelay;                // The maximum delay in ms experienced by segments that are delayed
    private static long seed;                   // The seed used for random number generator

    private static Random randomGenerator;      // The Random Number generator
    private static DatagramSocket senderSocket; // The UDP socket for the sender to send through
    private static InputStream inputReader;     // The reader for segmenting the data in the pdf
    private static File file;                   // The PDF file that is to be sent to the server
    private static int currentSeqNum;           // The current sequence number which we are up to sending
    private static int currentAckNum;           // The current acknowledgement number that the server has given us
    private static int dataSent;                // The amount of bytes that have been sent
    private static long timer;                  // A note of the time that the sender started sending
    private static PrintWriter writer;          // A writer for outputting a Log as text
    private static int unackedBytes;            // The number of bytes that have yet to be acknowledged
    private static int lastByteAcked;
    private static int lastByteSent;
    private static int initialSequenceNum;      // Initial sequence number

    private static int timeoutVal;              // Timeout value given to the socket
    private static double estimatedRTT;         // Used to calculate the timeout value for the socket
    private static double devRTT;               // Used to calculate the timeout value for the socket
    private static long[] timeSegmentSent;      // This array will tell what time a segment was sent

    private static int duplicateAcks;           // Counts the current number of duplicate ACK's received
    private static int totalDuplicateAcks;      // Counts the total number of duplicate ACK's received to log
    private static int forwardingCount;         // Count of number of packets forwarded

    private static DatagramPacket[] packetsSent;  // This array will store the packets sent to make it easier to
    // resend dropped packets
    private static DatagramPacket reorderedPacket;  // For the PLD to save the packet for re-Ordered sending

    private static LinkedList<Timer> timers;    // This linked list will be used to store all the timers created
    // during the execution of the program and close them all during shutdown.

    private static final int HEADER_SIZE = 17;
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
        System.out.println("--------------------------------------------");
        System.out.println("Starting the Stop and Wait Protocol...");

        // This counter will be used to fill in the segment Sent array to note down at what time each segment was
        // originally sent since retransmitted segment will not be be used to calculate sample RTT
        int counter = 0;

        while (true) {
            System.out.println(".....................");
            // Send data if there is still data left in the file to be sent, however if the unackedBytes has eclipsed
            // the maximum window size then stop sending and wait
            if (file.length() > dataSent && unackedBytes < mws) {
                // Create a byte array that will be attached to the UDP Packet to be sent
                byte[] udpData = new byte[mss + HEADER_SIZE];

                // Grab the data from the input reader and add it to byte array with an offset for the header. Then
                // attach the data to a packet and send it. read() also returns the number of bytes read so we store
                // this in a variable
                int bytesRead = inputReader.read(udpData, HEADER_SIZE, mss);

                // Create an STP header and append it to the top of the UDP Data
                STP stp = new STP(false, false, false, currentSeqNum, currentAckNum, 0);
                System.arraycopy(stp.getHeader(), 0, udpData, 0, HEADER_SIZE);

                // If the bytesRead is less than the mss, then we will create a smaller packet
                if (bytesRead < mss && bytesRead != -1) {
                    byte[] tempData = new byte[bytesRead + HEADER_SIZE];

                    // Calculate and add the checksum to the header before sending
                    byte[] packetData = new byte[bytesRead];
                    System.arraycopy(udpData, HEADER_SIZE, packetData, 0, bytesRead);
                    long checksum = calculateChecksum(packetData);
                    System.out.println("Checksum calculated as " + checksum);
                    stp.setChecksum(checksum);

                    System.arraycopy(stp.getHeader(), 0, tempData, 0, HEADER_SIZE);
                    System.arraycopy(udpData, HEADER_SIZE, tempData, HEADER_SIZE, bytesRead);

                    DatagramPacket dataPacket = new DatagramPacket(tempData, tempData.length, receiverHost,
                            receiverPort);

                    pldModule(dataPacket);
                    storePacket(dataPacket, counter);

                    // Update the book keeping
                    currentSeqNum += tempData.length - HEADER_SIZE;
                    dataSent += tempData.length - HEADER_SIZE;

                    System.out.println("Packet successfully sent! Data Sent: " + dataSent);

                } else {
                    // Calculate a checksum first and reevaluate the packet
                    byte[] tempData = new byte[mss];
                    System.arraycopy(udpData, HEADER_SIZE, tempData, 0, mss);
                    long checksum = calculateChecksum(tempData);
                    System.out.println("Checksum calculated as " + checksum);
                    stp.setChecksum(checksum);
                    System.arraycopy(stp.getHeader(), 0, udpData, 0, HEADER_SIZE);
                    DatagramPacket dataPacket = new DatagramPacket(udpData, udpData.length, receiverHost, receiverPort);

                    pldModule(dataPacket);
                    storePacket(dataPacket, counter);

                    // Update the book keeping
                    currentSeqNum += udpData.length - HEADER_SIZE;
                    dataSent += udpData.length - HEADER_SIZE;

                    System.out.println("Packet successfully sent! Data Sent: " + dataSent);
                }

                // After sending the data update the lastByteSent with the sequence number
                lastByteSent = currentSeqNum;
                counter++;
                System.out.println("last Byte sent was " + lastByteSent);

            } else {

                if (unackedBytes == 0) {
                    // End the Stop and Wait Protocol if all bytes of data have been sent and no more bytes are
                    // waiting to be acknowledged.
                    System.out.println("Stop and Wait Protocol complete");
                    System.out.println("--------------------------------------------");
                    break;
                }

                // Otherwise start accepting ACK packets from the Receiver, block until an ACK is received
                try {
                    System.out.println("Blocking while waiting for ACK...");
                    DatagramPacket ackPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);
                    senderSocket.receive(ackPacket);
                    long currentTime = System.currentTimeMillis(); // Note time the packet was received
                    printToLog(ackPacket, "rcv ");

                    // Update book keeping
                    STP stp = getHeaderFromPacket(ackPacket);
                    if (stp.getAckNum() > lastByteAcked) {
                        lastByteAcked = stp.getAckNum();
                    }

                    // Check if this is a duplicate ACK
                    if (lastByteAcked == stp.getAckNum()) {
                        duplicateAcks++;
                        totalDuplicateAcks++;
                        System.out.println("duplicateACKS = " + duplicateAcks);
                    }

                    // Fast transmit procedure, if 3 duplicate ACK's are received then we just retransmit the last
                    // package and reset the number of duplicate ACK's received.
                    if (duplicateAcks == 3) {
                        retransmitLastPacket();
                        duplicateAcks = 0;
                    }

                    System.out.println("ACK Received: " + stp.getAckNum());

                    // When the ACK is received, we recalculate the timeout value and set it for the socket
                    // Calculate the position in the segment sent array to get the time the original packet was sent
                    int i = (int) Math.ceil((stp.getAckNum() - mss - initialSequenceNum) / (double) mss);
                    long sampleRTT = currentTime - timeSegmentSent[i];
                    System.out.println("sampleRTT calculated as: " + sampleRTT);

                    estimatedRTT = 0.875 * estimatedRTT + 0.125 * sampleRTT;
                    devRTT = 0.75 * devRTT + 0.25 * Math.abs(sampleRTT - estimatedRTT);
                    timeoutVal = (int) (estimatedRTT + gamma * devRTT);
                    senderSocket.setSoTimeout(timeoutVal);

                } catch (SocketTimeoutException e) {
                    // When a timeout occurs we should resend the last packet that has not yet been acked
                    System.out.println("Sender Socket timed out...");
                    retransmitLastPacket();
                    }
            }

            unackedBytes = lastByteSent - lastByteAcked;
            System.out.println("UnackedBytes currently " + unackedBytes);
        }

        // File has been completely sent at this point. Initiate the shutdown of the connection
        if (!shutdownSender()) {
            System.out.println("Failed to teardown network");
            return;
        }

    }

    private static boolean bootstrapSender (String[] args) throws FileNotFoundException, UnsupportedEncodingException {
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
        maxDelay = Long.parseLong(args[12]);
        seed = Integer.parseInt(args[13]);

        randomGenerator = new Random(seed);

        // Initialise the estimatedRTT and devRTT to 500ms and 250ms as noted in the assignment spec
        estimatedRTT = 500;
        devRTT = 250;
        timeoutVal = (int) (estimatedRTT + gamma * devRTT);

        try {
            senderSocket = new DatagramSocket();
            senderSocket.setSoTimeout(timeoutVal);
        } catch (SocketException e) {
            System.out.println("Failed to setup UDP socket");
            e.printStackTrace();
            return false;
        }

        file = new File(fileName);
        inputReader = new FileInputStream(file);
        dataSent = 0;

        // The number of segments required to send the file will be the length of the file divided by the maximum
        // segment size + 1 for if there is a remainder
        int numberOfSegments = (int) Math.ceil((file.length() / (double) mss));
        System.out.println("Number of Segments " + numberOfSegments);
        timeSegmentSent = new long[numberOfSegments];
        packetsSent = new DatagramPacket[numberOfSegments];

        // Initialise the duplicate ACK counters
        duplicateAcks = 0;
        totalDuplicateAcks = 0;

        // Create a timer for the writer
        timer = System.currentTimeMillis();
        writer = new PrintWriter("Sender_log.txt", "UTF-8");
        timers = new LinkedList<>();

        // Print out the headers for each column into the log
        writer.print("evnt");
        writer.print(String.format("%7s", "time"));
        writer.print(String.format("%7s", "flag"));
        writer.print(String.format("%17s", "seq num"));
        writer.print(String.format("%7s", "bytes"));
        writer.println(String.format("%17s", "ack num"));
        writer.println("");

        return true;
    }

    private static boolean handshake() throws IOException {
        System.out.println("--------------------------------------------");
        System.out.println("Starting Handshake Procedure...");
        // Generate a random initial sequence number for security reasons. The random number generator generates 32
        // bit values
        int clientisn = randomGenerator.nextInt(100000) + 1;
        initialSequenceNum = clientisn;

        // Create Syn Packet and then sending it to the receiver
        System.out.println("Creating SYN Packet...");
        STP connectionRequest = new STP(false, true, false, clientisn, 0, 0);
        DatagramPacket synPacket = new DatagramPacket(connectionRequest.getHeader(), HEADER_SIZE, receiverHost,
                receiverPort);
        senderSocket.send(synPacket);
        printToLog(synPacket, "snd ");
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
        printToLog(synAckPacket, "rcv ");
        System.out.println("SYNACK Packet successfully received");

        // Retrieve the STP header from the SYNACK Packet
        STP synAckSTP = getHeaderFromPacket(synAckPacket);
        int serverisn = synAckSTP.getSequenceNum();

        // Sending out the Ack for the SYNACK segment
        System.out.println("Creating ACK Packet...");
        STP ackSTP = new STP(true, false, false, clientisn+1, serverisn+1,0);
        DatagramPacket ackPacket = new DatagramPacket(ackSTP.getHeader(), HEADER_SIZE, receiverHost, receiverPort);
        senderSocket.send(ackPacket);
        printToLog(ackPacket, "snd ");
        System.out.println("ACK Packet sent, three-way handshake complete");
        System.out.println("--------------------------------------------");
        // Store the correct sequence numbers and acknowledgement numbers
        currentAckNum = serverisn + 1;
        currentSeqNum = clientisn + 1;
        lastByteAcked = currentSeqNum;

        return true;
    }

    private static boolean shutdownSender() throws IOException {
        System.out.println("--------------------------------------------");
        System.out.println("Starting Network Teardown...");
        // Create a FIN Packet and send it to the Receiver
        System.out.println("Creating FIN Packet...");
        STP finHeader = new STP(false, false, true, currentSeqNum, currentAckNum,0);
        DatagramPacket finPacket = new DatagramPacket(finHeader.getHeader(), HEADER_SIZE, receiverHost, receiverPort);
        senderSocket.send(finPacket);
        printToLog(finPacket, "snd");
        System.out.println("FIN Packet sent");

        // Block while waiting for ACK
        System.out.println("Block while waiting for ACK");
        DatagramPacket dataPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);
        while (!checkSTPHeaderFlags(dataPacket, ACK_FLAG) && !checkSTPAckNum(dataPacket, currentSeqNum + 1)) {
            senderSocket.receive(dataPacket);
        }
        printToLog(dataPacket, "rcv");
        System.out.println("ACK for teardown received!");

        currentSeqNum += HEADER_SIZE;

        // Block while waiting for Receiver FIN
        System.out.println("Block while waiting for FIN");
        while (!checkSTPHeaderFlags(dataPacket, FIN_FLAG)) {
            senderSocket.receive(dataPacket);
        }
        printToLog(dataPacket, "rcv");
        System.out.println("FIN received!, sending ACK");

        // Create ACK Packet for Receiver
        STP finRecHeader = new STP(getHeaderFromPacket(dataPacket).getHeader());
        STP ackHeader = new STP(true, false, false, currentSeqNum, finRecHeader.getSequenceNum() + 1,0);
        DatagramPacket ackPacket = new DatagramPacket(ackHeader.getHeader(), HEADER_SIZE, receiverHost, receiverPort);
        senderSocket.send(ackPacket);
        printToLog(ackPacket, "snd");
        System.out.println("Final ACK sent. Teardown complete");
        System.out.println("--------------------------------------------");

        senderSocket.close();
        inputReader.close();
        writer.close();

        int i = 0;
        while (i < timers.size()) {
            Timer timer = timers.get(i);
            timer.cancel();
            i++;
        }

        return true;
    }

    private static void retransmitLastPacket() throws IOException {
        if (lastByteSent - lastByteAcked != 0) {
            System.out.println("Retransmitting package...");
            int i = (int) Math.ceil((lastByteAcked - initialSequenceNum - 1) / (double) mss);
            System.out.println("Attempting to send package in index: " + i);
            sendPacket(packetsSent[i], "RXT ");
        }
    }

    private static boolean checkSTPHeaderFlags(DatagramPacket packet, int flag) {
        STP header = getHeaderFromPacket(packet);
        return header.checkFlag(flag);
    }

    private static boolean checkSTPAckNum(DatagramPacket packet, int ackNum) {
        STP header = getHeaderFromPacket(packet);
        return header.getAckNum() == ackNum;
    }

    private static STP getHeaderFromPacket(DatagramPacket packet) {
        byte[] packetData = packet.getData();
        byte[] header = new byte[HEADER_SIZE];
        System.arraycopy(packetData, 0, header, 0, HEADER_SIZE);
        STP stpHeader = new STP(header);
        return stpHeader;
    }

    private static void printToLog(DatagramPacket datagramPacket, String event) {
        STP header = getHeaderFromPacket(datagramPacket);
        long currentTime = System.currentTimeMillis();

        // Print the type of event
        writer.print(event);

        // Print the time of event
        writer.print(String.format("%7s", currentTime - timer));

        // Check what flags are set in the header and print appropriately
        if(checkSTPHeaderFlags(datagramPacket, SYN_FLAG) && checkSTPHeaderFlags(datagramPacket, ACK_FLAG)) {
            writer.print(String.format("%7s", "SA"));
        } else if (checkSTPHeaderFlags(datagramPacket, SYN_FLAG)) {
            writer.print(String.format("%7s", "S"));
        } else if (checkSTPHeaderFlags(datagramPacket, ACK_FLAG)) {
            writer.print(String.format("%7s", "A"));
        } else if (checkSTPHeaderFlags(datagramPacket, FIN_FLAG)) {
            writer.print(String.format("%7s", "F"));
        } else {
            // If nothing else then it is just data
            writer.print(String.format("%7s", "D"));
        }

        // Print the Sequence Number
        writer.print(String.format("%17s", header.getSequenceNum()));

        // Print the Number of Bytes of Data
        if (datagramPacket.getLength() == HEADER_SIZE) {
            writer.print(String.format("%7s", 0));
        } else {
            writer.print(String.format("%7s", datagramPacket.getLength() - HEADER_SIZE));
        }

        // Print the Acknowledgement Number
        writer.println(String.format("%17s", header.getAckNum()));
    }

    private static void dropPackets(DatagramPacket dataPacket) {
        printToLog(dataPacket, "drop");
        System.out.println("PACKET DROPPED");
    }

    private static void duplicatePackets(DatagramPacket dataPacket) throws IOException {
        sendPacket(dataPacket, "snd ");
        sendPacket(dataPacket, "dup ");
        System.out.println("DUPLICATED");
    }

    private static void sendCorruptPacket(DatagramPacket packet) throws IOException {
        byte[] packetData = new byte[packet.getData().length];
        System.arraycopy(packet.getData(), 0, packetData, 0, packetData.length);

        // Corrupts the first byte after the Header by flipping all the bits
        packetData[HEADER_SIZE + 1] = (byte) ~packetData[HEADER_SIZE + 1];
        DatagramPacket dataPacket = new DatagramPacket(packetData, packetData.length, receiverHost, receiverPort);
        sendPacket(dataPacket, "corr");
        System.out.println("CORRUPTED");
    }

    private static long calculateChecksum(byte[] data) {
        Checksum checksum = new CRC32();
        checksum.update(data,0, data.length);
        return checksum.getValue();
    }

    private static void reorderPacket(DatagramPacket packet) throws IOException {
        // First check if there is a packet already being reordered, if there is then send it first and then replace
        // it with the new reordered packet
        if (reorderedPacket != null) {
            senderSocket.send(reorderedPacket);
            printToLog(reorderedPacket, "rord");
            forwardingCount = 0;
        }
        reorderedPacket = packet;
    }

    private static void sendPacket(DatagramPacket packet, String event) throws IOException {
        // Sends the packet and increments forwarding count only if there is a packet saved
        senderSocket.send(packet);
        printToLog(packet, event);

        if (reorderedPacket != null) {
            forwardingCount++;
        }

        // Checks if the forwardingCount has reached maxOrder. If it has reached maxOrder then we also send the
        // reordered Packet and reset the reorderedPacket to null.
        if (forwardingCount == maxOrder && maxOrder != 0) {
            senderSocket.send(reorderedPacket);
            printToLog(reorderedPacket, "rord");
            forwardingCount = 0;
            reorderedPacket = null;
        }
    }

    private static void delayPacket(final DatagramPacket datagramPacket) {
        // Generate a random delay between 0 and maxDelay
        long x = 0;
        long y = maxDelay;
        long randomDelay = x + ((long)(randomGenerator.nextDouble()*(y-x)));

        // Create a timer and schedule the task of sending the delayed packet
        Timer timer = new Timer();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                try {
                    sendPacket(datagramPacket, "dely");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        };
        timer.schedule(task, randomDelay);
        // Add the timer to the linked list
        timers.add(timer);
    }

    private static void pldModule(DatagramPacket dataPacket) throws IOException {
        // PLD module
        if (randomGenerator.nextDouble() > pDrop) {
            if (randomGenerator.nextDouble() > pDuplicate) {
                if (randomGenerator.nextDouble() > pCorrupt) {
                    if (randomGenerator.nextDouble() > pOrder) {
                        if (randomGenerator.nextDouble() > pDelay) {
                            sendPacket(dataPacket, "snd ");
                        } else {
                            delayPacket(dataPacket);
                        }
                    } else {
                        reorderPacket(dataPacket);
                    }
                } else {
                    sendCorruptPacket(dataPacket);
                }
            } else {
                duplicatePackets(dataPacket);
            }
        } else {
            dropPackets(dataPacket);
        }
    }

    private static void storePacket(DatagramPacket dataPacket, int counter) {
        // Note the time that the packet was first sent, and then store the packet for retransmission if necessary
        timeSegmentSent[counter] = System.currentTimeMillis();
        packetsSent[counter] = dataPacket;
        System.out.println("Packet stored in index " + counter);
    }

}
