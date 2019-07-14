import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.net.InetAddress.getByAddress;
import static java.net.InetAddress.getByName;

/**
 * Main class. Opens a proxy socket on port 8080 and a thread pool for up to 20 clients.
 */
public class Sockspy {
    public static void main(String[] args) throws IOException {
        ServerSocket proxySocket = new ServerSocket(8080);
            ExecutorService executor = Executors.newFixedThreadPool(20);
            while (true) {
                SockProxyThread client = new SockProxyThread(proxySocket.accept());
                executor.execute(client);
        }
    }
}

class SockProxyThread extends Thread {
    private static final byte REQUEST_GRANTED = 90;
    private static final byte REQUEST_FAILED = 91;
    private static final int VERSION_NUMBER = 4;
    private static final int COMMAND_CODE = 1;
    private static final int TIMEOUT_TIME = 5000;

    private Socket clientSocket;
    private Socket dstSocket;
    private InetAddress dstAddress;
    private CountDownLatch countDownLatch;
    private int vn;
    private int cd;
    private int dstPort;
    private byte replyCode;
    private byte[] dstIp;

    public SockProxyThread(Socket clientSocket) {
        this.clientSocket = clientSocket;
        dstIp = new byte[4];
    }

    /**
     * Upon receiving a client request, sets up a connection with the requested destination
     * and relays data from the destination to the client
     */
    @Override
    public void run() {
        try {
            if(getRequest(clientSocket.getInputStream())) {
                connectToDstSocket();
                sendAck();
            }
            else {
                sendAck();
                closeConnections();
                return;
            }
        } catch (IOException e) {
            closeConnections();
        }

        if (replyCode == REQUEST_GRANTED) {
            try {
                relay();
                closeConnections();
            } catch (SocketException e) {
                closeConnections();
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Receives request from client and extracts the Socks version number, command code, destination port
     * and destination address.
     * @param inFromClient - The input stream from the client
     * @return true if the version number and command codes are valid, otherwise returns false
     * @throws IOException
     */
    private boolean getRequest(InputStream inFromClient) throws IOException {
        vn = inFromClient.read();
        cd = inFromClient.read();
        boolean validRequest = validateRequest();

        if(validRequest) {
            dstPort = ((inFromClient.read() << 8) | inFromClient.read());
            inFromClient.read(dstIp);
            dstAddress = getByAddress(dstIp);
            if(!socks4a(inFromClient)) {
                while (inFromClient.read() != 0) {
                    continue;
                }
            }
        }

        return validRequest;
    }

    /**
     * Checks the validity of the version number and the command codes. If invalid prints error
     * message to std err
     * @return true if the version number and command codes are valid, otherwise returns false
     */
    private boolean validateRequest() {
        boolean validRequest = true;

        if (vn != VERSION_NUMBER) {
            replyCode = REQUEST_FAILED;
            validRequest = false;
            System.err.println("Connection error: while parsing request: Unsupported SOCKS protocol version" +
                    " (got " + vn + ")");
        } else if (cd != COMMAND_CODE) {
            replyCode = REQUEST_FAILED;
            validRequest = false;
            System.err.println("Connection error: while parsing request: Unsupported SOCKS command" +
                    " (got " + cd + ")");
        }

        return validRequest;
    }

    /**
     * Opens a socket and attempts to connect to the required destination.
     */
    private void connectToDstSocket() {
        try {
            dstSocket = new Socket();
            dstSocket.setSoTimeout(10 * TIMEOUT_TIME);
            dstSocket.connect(new InetSocketAddress(dstAddress, dstPort), TIMEOUT_TIME);
            replyCode = REQUEST_GRANTED;
            System.err.println("Successful" + buildOutputMessage());
        } catch (IOException e) {
            replyCode = REQUEST_FAILED;
            System.err.println("Connection error: while connecting to destination: " + e.getMessage());
        }
    }

    /**
     * Checks for the SOCKS 4A extension
     * @param inFromClient - The input stream from the client
     * @return - true if the given protocol is SOCKS version 4a, otherwise false
     * @throws IOException
     */
    private boolean socks4a(InputStream inFromClient) throws IOException {
        boolean is4A = false;

        if (dstIp[0] == 0 && dstIp[1] == 0 && dstIp[2] == 0 && dstIp[3] != 0) {
            is4A = true;
            StringBuilder host = new StringBuilder();
            int bytes;
            while ((bytes = inFromClient.read()) != 0) {
            }

            while ((bytes = inFromClient.read()) != 0) {
                host.append((char) bytes);
            }

            dstAddress = getByName(host.toString());
        }

        return is4A;
    }

    /**
     * Sends the acknowledgement to the client
     */
    private void sendAck() {
        byte[] reply = buildAck();
        try {
            OutputStream response = clientSocket.getOutputStream();
            response.write(reply);
            response.flush();
        } catch (IOException e) {
        }
    }

    /**
     * Generates the appropriate acknowledgement
     * @return - byte array containing the required acknowledgement
     */
    private byte[] buildAck() {
        byte[] reply = new byte[8];
        reply[0] = 0;
        reply[1] = replyCode;
        reply[2] = (byte) (dstPort >> 8);
        reply[3] = (byte) dstPort;

        for (int i = 0; i < 4; i++) {
            reply[i + 4] = dstIp[i];
        }

        return reply;
    }

    /**
     * Generates the output message
     * @return - Message to be printed to std err
     */
    private String buildOutputMessage() {
        StringBuilder message = new StringBuilder();
        message.append(" connection from " + clientSocket.getInetAddress().getHostAddress() +
                ":" + clientSocket.getPort());

        if (replyCode == REQUEST_GRANTED) {
            message.append(" to " + dstAddress.getHostAddress() + ":" + dstPort);
        }

        return message.toString();
    }

    /**
     * Creates threads from client to destination and from destination to client. Relays data from client to
     * destination and from destination to client. To make sure only one thread is in transition
     * a countdown latch is used
     * @throws SocketException
     * @throws InterruptedException
     */
    private void relay() throws SocketException, InterruptedException {
        clientSocket.setSoTimeout(TIMEOUT_TIME);
        countDownLatch = new CountDownLatch(1);
        RelayThread relayClientDestination = new RelayThread(clientSocket, dstSocket, countDownLatch);
        RelayThread relayDestinationClient = new RelayThread(dstSocket, clientSocket, countDownLatch);

        relayClientDestination.start();
        relayDestinationClient.start();
        countDownLatch.await();
    }

    /**
     * Closes client and destination sockets
     */
    private void closeConnections() {
        System.err.println("Closing" + buildOutputMessage());
        try {
            clientSocket.close();
            if (dstSocket != null)
                dstSocket.close();
        } catch (IOException e) {
        }
    }
}

class RelayThread extends Thread {
    private Socket clientSocket;
    private Socket dstSocket;
    private int dstPort;
    private CountDownLatch countDownLatch;

    public RelayThread(Socket clientSocket, Socket dstSocket, CountDownLatch countDownLatch) {
        this.clientSocket = clientSocket;
        this.dstSocket = dstSocket;
        this.dstPort = dstSocket.getPort();
        this.countDownLatch = countDownLatch;
    }

    /**
     * Relays data from client to destination
     */
    @Override
    public void run() {
        StringBuilder message = new StringBuilder();
        int bytes;
        byte[] buffer = new byte[1024];

        try {
            InputStream readClient = clientSocket.getInputStream();
            OutputStream writeDest = dstSocket.getOutputStream();

            while((bytes = readClient.read(buffer)) != -1) {
                if(bytes > 0) {
                   writeDest.write(buffer, 0, bytes);
                   writeDest.flush();

                    for(byte b : buffer) {
                        message.append((char) b);
                    }
                }
            }

            if(dstPort == 80 && message.toString().contains("GET")) {
                authorize(message.toString());
            }

        } catch (IOException e) {
        }

        countDownLatch.countDown();
    }

    /**
     * Checks if the received data is a HTTP GET request. If so, decodes authorization password and
     * prints is to std err
     * @param inputMessage - String received from the client
     */
    private void authorize(String inputMessage) {
        StringBuilder outputMessage = new StringBuilder();
        Pattern pattern = Pattern.compile("GET (.*)\r\nHost: (.*)\r\nAuthorization: Basic (.*)\r\n");
        Matcher matcher = pattern.matcher(inputMessage);
        if(matcher.find()) {
            outputMessage.append("Password Found! http://");
            String decodedPassword = new String(Base64.getDecoder().decode(matcher.group(3)));
            outputMessage.append(decodedPassword);
            outputMessage.append("@" + matcher.group(2));
            String path = matcher.group(1).substring(0, matcher.group(1).indexOf(" "));
            outputMessage.append(path);
        }

        System.err.println(outputMessage);
    }
}

