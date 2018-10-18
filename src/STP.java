import java.nio.ByteBuffer;
import java.util.BitSet;

public class STP {
    public boolean isAck;
    public boolean isSyn;
    public boolean isFin;
    public int sequenceNum;
    public int ackNum;
    private byte[] header;

    private static final int HEADER_SIZE = 9;
    private static final int ACK_FLAG = 0;
    private static final int SYN_FLAG = 1;
    private static final int FIN_FLAG = 2;
    private static final int SEQUENCENUM_POS = 0;
    private static final int ACKNUM_POS = 4;
    private static final int FLAG_POS = 5;
    private static final int SEQUENCENUM_SIZE = 4;
    private static final int ACKNUM_SIZE = 4;
    private static final int FLAG_SIZE = 1;
    private static final int ARRAY_START = 0;

    /*
        Create an STP object when given explicit variables for header construction
     */
    public STP(boolean isAck, boolean isSyn, boolean isFin, int sequenceNum, int ackNum) {
        this.isAck = isAck;
        this.isSyn = isSyn;
        this.isFin = isFin;
        this.sequenceNum = sequenceNum;
        this.ackNum = ackNum;
        this.header = createSTPHeader();
    }

    /**
     * Creaes an STP object when given a byte array
     * @param header
     */
    public STP(byte[] header) {
        // Separate the header into it's components
        byte[] sequenceNumByteArray = new byte[SEQUENCENUM_SIZE];
        byte[] ackNumByteArray = new byte[ACKNUM_SIZE];
        byte[] flagsByteArray = new byte[FLAG_SIZE];

        System.arraycopy(header, SEQUENCENUM_POS, sequenceNumByteArray, ARRAY_START, SEQUENCENUM_SIZE);
        System.arraycopy(header, ACKNUM_POS, ackNumByteArray, ARRAY_START, ACKNUM_SIZE);
        System.arraycopy(header, FLAG_POS, flagsByteArray, ARRAY_START, FLAG_SIZE);
        BitSet flagBitSet = BitSet.valueOf(flagsByteArray);

        this.isAck = flagBitSet.get(ACK_FLAG);
        this.isSyn = flagBitSet.get(SYN_FLAG);
        this.isFin = flagBitSet.get(FIN_FLAG);
        this.sequenceNum = byteArrayToInt(sequenceNumByteArray);
        this.ackNum = byteArrayToInt(ackNumByteArray);
        this.header = header;
    }

    public byte[] createSTPHeader() {
        /*
            The STP header looks like this:
            Sequence Number (4 Bytes)
            Acknowledgement Number (4 Bytes)
            Flags(1 Byte)
         */
        byte[] header = new byte[HEADER_SIZE];

        // Create the byte arrays for all the portions in the STP Header
        byte[] sequenceNumByte = intToByteArray(sequenceNum);
        byte[] ackNumByte = intToByteArray(ackNum);
        BitSet flagBitSet = new BitSet(8);
        if (isAck) {
            flagBitSet.set(ACK_FLAG);
        }

        if (isSyn) {
            flagBitSet.set(SYN_FLAG);
        }

        if (isFin) {
            flagBitSet.set(FIN_FLAG);
        }
        byte[] flags = flagBitSet.toByteArray();

        // Copy the byte arrays into the header byte array
        System.arraycopy(sequenceNumByte, 0, header, SEQUENCENUM_POS, sequenceNumByte.length);
        System.arraycopy(ackNumByte, 0, header, ACKNUM_POS, ackNumByte.length);
        System.arraycopy(flags, 0, header, FLAG_POS, flags.length);

        return header;
    }

    /**
     * Checks if the flag was used
     * @param flag
     * @return
     */
    public boolean checkFlag(int flag) {
        if (flag == SYN_FLAG) {
            return this.isSyn;
        }

        if (flag == ACK_FLAG) {
            return this.isAck;
        }

        if (flag == FIN_FLAG) {
            return this.isFin;
        }

        return false;
    }

    /**
     * Compares the given number with the ackNum of the STP header
     * @param ackNum
     * @return
     */
    public boolean checkAckNum(int ackNum) {
        return ackNum == this.ackNum;
    }

    /**
     * Takes a 4 byte (32 Bit) Java Integer and converts it into a 4 byte array.
     * @param number
     * @return
     */
    private static byte[] intToByteArray(int number) {
        byte[] binaryNum = new byte[4]; // Int in Java are 32 bits
        binaryNum[0] = (byte) (number >> 24);
        binaryNum[1] = (byte) (number >> 16);
        binaryNum[2] = (byte) (number >> 8);
        binaryNum[3] = (byte) (number);
        return binaryNum;
    }

    /**
     * Takes a 4 byte array and converts it into a Java Integer
     * @param intArray
     * @return
     */
    private static int byteArrayToInt(byte[] intArray) {
        ByteBuffer wrap = ByteBuffer.wrap(intArray);
        return wrap.getInt();
    }

    public byte[] getHeader() {
        return this.header;
    }

    public int getSequenceNum() {
        return this.sequenceNum;
    }

    public int getAckNum() {
        return this.ackNum;
    }
}
