import javax.xml.crypto.Data;
import java.io.*;
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
    private static int mss;
    private static byte[][] dataBuffer;
    private static int senderisn;
    private static int receiverisn;
    private static PrintWriter writer;
    private static long timer;

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

        // A TCP packet has a maximum size of 65535 bytes however in reality, most packets are very much smaller than
        // this.
        DatagramPacket dataPacket = new DatagramPacket(new byte[65535], 65535);
        STP packetSTP;
        int segmentSize;

        // After handshake has been completed, Block the server waiting for packets
        while (true) {
            System.out.println("--------------------------------------------");
            System.out.println("Block while waiting for data packet...");
            receiverSocket.receive(dataPacket);
            printToLog(dataPacket, "rcv");
            System.out.println("A Packet was received");
            packetSTP = getHeaderFromPacket(dataPacket);

            // Check if the packet received is a FIN Packet, if so then break and initiate shutdown
            if (checkSTPHeaderFlags(dataPacket, FIN_FLAG)) {
                System.out.println("FIN Packet received, initiating shutdown");
                break;
            }

            // Check if this is the first packet arriving from the sender, if it is then we will note down the
            // maximum segment size and create a buffer to store the data.
            if (packetSTP.getSequenceNum() == senderisn + 1) {
                System.out.println("This is the first packet...");
                mss = dataPacket.getLength() - HEADER_SIZE;
                dataBuffer = new byte[mss][];
            }

            // Check if the packets are out of order...
            if (currentAckNum != packetSTP.getSequenceNum()) {
                // TODO
            }

            // Since it is not a FIN Packet then we simply ACK the packet.
            // First get the sequence number from the packet we just received and ACK back the sequence number + the
            // number of bytes received. The number of bytes sent is the length of the UDP datapacket - the length of
            // the STP header.
            System.out.println("Storing data from Packet into byte array...");
            copyToBuffer(dataPacket);
            System.out.println("Data stored!");

            segmentSize = dataPacket.getLength() - HEADER_SIZE;
            currentAckNum += segmentSize;
            currentSeqNum += 1;

            System.out.println("Creating ACK Packet...");
            STP ackSegment = new STP(true, false, false, currentSeqNum, currentAckNum);
            DatagramPacket ackPacket = new DatagramPacket(ackSegment.getHeader(), ackSegment.getHeader().length,
                    sourceAddress, sourcePort);

            receiverSocket.send(ackPacket);
            printToLog(ackPacket, "snd");

            //FOR TESTING
            STP stp = getHeaderFromPacket(ackPacket);

            System.out.println("ACK Packet successfully sent. Ack Num: " + ackSegment.getAckNum());
            System.out.println("--------------------------------------------");
        }

        // Initiate the shutdown between Sender and Receiver
        if (!shutdownReceiver(dataPacket)) {
            System.out.println("Failed to teardown network");
            return;
        }

        writeDataOut();

    }

    private static boolean bootstrapReceiver (String[] args) throws FileNotFoundException, UnsupportedEncodingException {
        receiverPort = Integer.parseInt(args[0]);
        fileName = args[1];

        try {
            receiverSocket = new DatagramSocket(receiverPort);
        } catch (SocketException e) {
            System.out.println("Failed to setup UDP socket");
            e.printStackTrace();
            return false;
        }

        writer = new PrintWriter("Receiver_log.txt", "UTF-8");
        timer = System.currentTimeMillis();

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

    private static boolean handshake () throws IOException {
        System.out.println("--------------------------------------------");
        System.out.println("Starting handshake procedure...");
        // Create a Datagram Packet to store the incoming Syn Packet.
        DatagramPacket synPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);

        // Block while waiting for the initial SYN Packet to arrive.
        System.out.println("Block while waiting for SYN Packet to arrive...");
        while (!checkSTPHeaderFlags(synPacket, SYN_FLAG)) {
            receiverSocket.receive(synPacket);
        }

        // Once you have accepted the original SYN Packet, note down the address and port number of the source in
        // order to send ACK packets back. Note down the ISN in order to ACK the correct packet.
        printToLog(synPacket, "rcv");
        System.out.println("SYN successfully received");
        sourceAddress = synPacket.getAddress();
        sourcePort = synPacket.getPort();
        STP synSegment = getHeaderFromPacket(synPacket);
        senderisn = synSegment.getSequenceNum();
        receiverisn = 0;

        // Create a SYNACK Packet and send it back to the host.
        System.out.println("Creating SYNACK Packet...");
        STP synAckSegment = new STP(true, true, false, receiverisn, senderisn + 1);
        DatagramPacket synAckPacket = new DatagramPacket(synAckSegment.getHeader(), synAckSegment.getHeader().length,
                sourceAddress, sourcePort);
        receiverSocket.send(synAckPacket);
        printToLog(synAckPacket, "snd");
        System.out.println("SYNACK Packet successfully sent");

        // Create a Datagram Packet to store the incoming Ack Packet
        DatagramPacket ackPacket = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);

        // Block while waiting for final ACK Packet to signal that the three-way handshake is complete.
        System.out.println("Block while waiting for final ACK Packet to arrive...");
        while (!checkSTPHeaderFlags(ackPacket, ACK_FLAG) && !checkSTPAckNum(ackPacket, receiverisn + 1)) {
            receiverSocket.receive(ackPacket);
        }
        currentSeqNum = receiverisn + 1;
        currentAckNum = senderisn + 1;
        printToLog(ackPacket, "rcv");
        System.out.println("ACK successfully received, three way handshake complete");
        System.out.println("--------------------------------------------");

        return true;
    }

    private static boolean shutdownReceiver(DatagramPacket initFinPacket) throws IOException {
        System.out.println("--------------------------------------------");
        System.out.println("FIN Packet received. Initiate network teardown...");
        // After Receiving the FIN Packet we must ACK the Packet
        STP ackHeader = new STP(true, false, false, currentSeqNum, currentAckNum + 1);
        DatagramPacket ackPacket1 = new DatagramPacket(ackHeader.getHeader(), HEADER_SIZE, sourceAddress, sourcePort);
        receiverSocket.send(ackPacket1);
        printToLog(ackPacket1, "snd");

        // Create a FIN Packet and send it to the Sender
        System.out.println("Creating FIN Packet...");
        STP finHeader = new STP(false, false, true, currentSeqNum, currentAckNum);
        DatagramPacket finPacket = new DatagramPacket(finHeader.getHeader(), HEADER_SIZE, sourceAddress, sourcePort);
        receiverSocket.send(finPacket);
        printToLog(finPacket, "snd");
        System.out.println("FIN Packet sent!");

        //Block while waiting for ACK
        System.out.println("Block while waiting for ACK...");
        DatagramPacket ackPacket2 = new DatagramPacket(new byte[HEADER_SIZE], HEADER_SIZE);
        while (!checkSTPHeaderFlags(ackPacket2, ACK_FLAG) && !checkSTPAckNum(ackPacket2, currentSeqNum + 1)) {
            receiverSocket.receive(ackPacket2);
        }
        printToLog(ackPacket2, "rcv");
        System.out.println("ACK Received. Receiver successfully closed");
        System.out.println("--------------------------------------------");

        return true;
    }

    private static void copyToBuffer (DatagramPacket datagramPacket) {
        // Copy the data from the Packet, without the header
        byte[] data = new byte[mss];

        System.arraycopy(datagramPacket.getData(), HEADER_SIZE, data, 0, mss);

        // Calculate which packet this is in order to place it in the correct location within the buffer
        STP stp = getHeaderFromPacket(datagramPacket);
        int packetNum = (stp.getSequenceNum() - senderisn - 1) / mss;
        dataBuffer[packetNum] = data;
    }

    private static void writeDataOut() throws IOException {
        System.out.println("--------------------------------------------");
        // Write the data from the buffer into a file
        System.out.println("Writing data out from buffer to file...");
        BufferedWriter fileWriter = new BufferedWriter(new FileWriter(fileName));
        for (int i = 0; i < dataBuffer.length; i++) {
            if (dataBuffer[i] != null) {
                fileWriter.write(new String(dataBuffer[i]));
            } else {
                break;
            }
        }

        fileWriter.close();
        writer.close();

        System.out.println("Data copied successfully into file: " + fileName);
        System.out.println("--------------------------------------------");
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
        return header.getAckNum() == ackNum;
    }

    /**
     * Gets the STP header from a Datagram Packet
     * @param datagramPacket
     * @return
     */
    private static STP getHeaderFromPacket (DatagramPacket datagramPacket) {
        byte[] packetData = datagramPacket.getData();
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
}
