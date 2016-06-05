/*
Copyright 2007 Nominet UK
Copyright 2016 Blue Lotus Software, LLC.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License. 
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0 

Unless required by applicable law or agreed to in writing, software 
distributed under the License is distributed on an "AS IS" BASIS, 
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
See the License for the specific language governing permissions and 
limitations under the License.
 */
package uk.nominet.dnsjnio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.text.MessageFormat;
import java.util.List;
import java.util.Random;
import org.apache.log4j.Logger;
import org.xbill.DNS.*;

/**
 * A nonblocking implementation of Resolver. Multiple concurrent sendAsync
 * queries can be run without increasing the number of threads.
 *
 * @author Alex Dalitz <alex@caerkettontech.com>
 * @author John Yeary <jyeary@bluelotussoftware.com>
 * @author Allan O'Driscoll
 */
public class NonblockingResolver implements INonblockingResolver {

    private static final Logger LOG = Logger.getLogger(NonblockingResolver.class);
    /**
     * The default port to send queries to
     */
    public static final int DEFAULT_PORT = 53;

    private InetSocketAddress remoteAddress = new InetSocketAddress(DEFAULT_PORT);

    private boolean useTCP = false, ignoreTruncation;

    // private byte EDNSlevel = -1;
    private TSIG tsig;

    private int timeoutValue = 10 * 1000;

    /**
     * The default EDNS payload size
     */
    public static final int DEFAULT_EDNS_PAYLOADSIZE = 1280;

    private static final short DEFAULT_UDPSIZE = 512;

    private OPTRecord queryOPT;

    private static String defaultResolver = "localhost";

    // Use short as id because the id header is limited to 16 bit
    // From RFC1035 4.1.1. Header section format :
    // 
    // ID A 16 bit identifier assigned by the program that
    // generates any kind of query. This identifier is copied
    // the corresponding reply and can be used by the requester
    // to match up replies to outstanding queries.
    //
    private static short uniqueID = 0;
    private static Random random = new SecureRandom();
    private SinglePortTransactionController transactionController;
    private boolean useSingleTCPPort = false;
    private boolean useSingleUDPPort = false;

    /**
     * Use a random port by default.
     */
    private InetSocketAddress localAddress = new InetSocketAddress(0);

    /**
     * Creates a SimpleResolver that will query the specified host
     *
     * @param hostname The hostname of the DNS server to use. If the value is
     * {@code null}, the {@link ResolverConfig#currentConfig} is checked. If the
     * value is found, it is used. If the value is not found,
     * <strong>localhost</strong> is used. It the value is "0", then the
     * <strong>localhost</strong> is used. Otherwise it will attempt to resolve
     * the hostname address and use it for the remote server address. The
     * hostname can either be a machine name, such as "java.sun.com", or a
     * textual representation of its IP address. If a literal IP address is
     * supplied, only the validity of the address format is checked.
     * @exception UnknownHostException Failure occurred while finding the host
     */
    public NonblockingResolver(String hostname) throws UnknownHostException {
        if (hostname == null) {
            hostname = ResolverConfig.getCurrentConfig().server();
            if (hostname == null) {
                hostname = defaultResolver;
            }
        }
        InetAddress addr;
        if (hostname.equals("0")) {
            addr = InetAddress.getLocalHost();
        } else {
            addr = InetAddress.getByName(hostname);
        }
        remoteAddress = new InetSocketAddress(addr, DEFAULT_PORT);
        transactionController = new SinglePortTransactionController(
                remoteAddress, localAddress);
    }

    /**
     * Creates a SimpleResolver. The host to query is either found by using
     * ResolverConfig, or the default host is used.
     *
     * @see #NonblockingResolver(java.lang.String)
     * @see ResolverConfig
     * @exception UnknownHostException Failure occurred while finding the host
     */
    public NonblockingResolver() throws UnknownHostException {
        this(null);
    }

    InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * Sets the default host (initially localhost) to query
     *
     * @param hostname The name of the host which will serve as the default for
     * name resolution.
     */
    public static void setDefaultResolver(final String hostname) {
        defaultResolver = hostname;
    }

    /**
     * Sets the address of the server to communicate with.
     *
     * @param addr The address of the DNS server
     */
    public void setRemoteAddress(InetSocketAddress addr) {
        remoteAddress = addr;
        transactionController.setRemoteAddress(remoteAddress);
    }

    /**
     * Sets the address of the server to communicate with (on the default DNS
     * port)
     *
     * @param addr The address of the DNS server
     */
    public void setRemoteAddress(InetAddress addr) {
        remoteAddress = new InetSocketAddress(addr, remoteAddress.getPort());
        transactionController.setRemoteAddress(remoteAddress);
    }

    /**
     * Sets the server port to communicate on.
     *
     * @param port The server DNS port
     */
    public void setRemotePort(int port) {
        remoteAddress = new InetSocketAddress(remoteAddress.getAddress(), port);
        transactionController.setRemoteAddress(remoteAddress);
    }

    /**
     * Sets the local address to bind to when sending messages. If useSinglePort
     * is false then random ports will be used.
     *
     * @param addr The local address to send messages from.
     */
    public void setLocalAddress(InetSocketAddress addr) {
        localAddress = addr;
        transactionController.setLocalAddress(localAddress);
    }

    /**
     * Sets the local address to bind to when sending messages. A random port
     * will be used.
     *
     * @param addr The local address to send messages from.
     */
    public void setLocalAddress(InetAddress addr) {
        localAddress = new InetSocketAddress(addr, 0);
        transactionController.setLocalAddress(localAddress);
    }

    /**
     * Sets the server DNS port
     *
     * @param port the server port
     */
    @Override
    public void setPort(int port) {
        setRemotePort(port);
    }

    /**
     * Get the address we're sending queries from
     *
     * @return the local address
     */
    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public void setTCP(boolean flag) {
        this.useTCP = flag;
    }

    public boolean isTCP() {
        return useTCP;
    }

    @Override
    public void setIgnoreTruncation(boolean flag) {
        this.ignoreTruncation = flag;
    }

    /**
     * {@inheritDoc}
     * <p>
     * <strong>Note:</strong> Enabling this feature could open DNS cache
     * poisoning vulnerability.
     * </p>
     *
     * @see #setUseSingleUDPPort(boolean)
     */
    @Override
    public void setSingleTcpPort(boolean useSamePort) {
        this.useSingleTCPPort = useSamePort;
    }

    /**
     * In single port mode?
     *
     * @return {@literal true} if a single port should be used for all queries.
     * @see #setSingleTcpPort(boolean)
     * @see #setUseSingleUDPPort(boolean)
     */
    public boolean isSingleTcpPort() {
        return useSingleTCPPort;
    }

    /**
     * Determine if all UDP queries should use a single port.
     *
     * @return {@literal true} if a single port should be used for all queries.
     */
    public boolean isUseSingleUDPPort() {
        return useSingleUDPPort;
    }

    /**
     * <p>
     * Set the server to use a single port for sending UDP queries. The default
     * value is {@literal false}.</p>
     * <p>
     * <strong>Note:</strong> if the size of the {@link DatagramPacket} is too
     * large, it will be sent using TCP.</p>
     * <p>
     * <strong>Note:</strong> setting this value to {@literal true} may make
     * your application subject to Kaminsky attacks.</p>
     * <ul>
     * <li><a href="http://www.kb.cert.org/vuls/id/800113">Multiple DNS
     * implementations vulnerable to cache poisoning</a></li>
     * <li><a href="http://www.unixwiz.net/techtips/iguide-kaminsky-dns-vuln.html">An
     * Illustrated Guide to the Kaminsky DNS Vulnerability</a></li>
     * <li><a href="https://cve.mitre.org/cgi-bin/cvename.cgi?name=CVE-2008-1447">CVE-2008-1447</a></li>
     * </ul>
     *
     * @param useSingleUDPPort {@literal true} to enable and {@literal false} to
     * disable.
     */
    public void setUseSingleUDPPort(boolean useSingleUDPPort) {
        this.useSingleUDPPort = useSingleUDPPort;
    }

    /**
     * Sets the local port to bind to when sending messages. A random port will
     * be used if useSinglePort is false. THIS ONLY WORKS FOR TCP-BASED QUERIES
     * - UDP QUERIES WILL ALWAYS USE A RANDOM PORT
     *
     * @param port The local port to send messages from.
     */
    public void setLocalTcpPort(int port) {
        localAddress = new InetSocketAddress(localAddress.getHostName(), port);
        transactionController.setLocalAddress(localAddress);
    }

    @Override
    public void setEDNS(int level, int payloadSize, int flags, List options) {
        if (level != 0 && level != -1) {
            throw new IllegalArgumentException("invalid EDNS level - "
                    + "must be 0 or -1");
        }
        if (payloadSize == 0) {
            payloadSize = DEFAULT_EDNS_PAYLOADSIZE;
        }
        queryOPT = new OPTRecord(payloadSize, 0, level, flags, options);
    }

    @Override
    public void setEDNS(int level) {
        setEDNS(level, 0, 0, null);
    }

    private void applyEDNS(Message query) {
        if (queryOPT == null || query.getOPT() != null) {
            return;
        }
        query.addRecord(queryOPT, Section.ADDITIONAL);
    }

    @Override
    public void setTSIGKey(TSIG key) {
        tsig = key;
    }

    public void setTSIGKey(Name name, byte[] key) {
        tsig = new TSIG(name, key);
    }

    public void setTSIGKey(String name, String key) {
        tsig = new TSIG(name, key);
    }

    protected TSIG getTSIGKey() {
        return tsig;
    }

    @Override
    public void setTimeout(int secs) {
        setTimeout(secs, 0);
    }

    @Override
    public void setTimeout(int secs, int millisecs) {
        timeoutValue = (secs * 1000) + millisecs;
    }

    // For backwards compatability
    int getTimeout() {
        return timeoutValue / 1000;
    }

    // For use by ENBR, but probably useful for clients! Not in standard Resolver interface, though
    public int getTimeoutMillis() {
        return timeoutValue;
    }

    private int maxUDPSize(Message query) {
        OPTRecord opt = query.getOPT();
        if (opt == null) {
            return DEFAULT_UDPSIZE;
        } else {
            return opt.getPayloadSize();
        }
    }

    /**
     * Sends a message to a single server and waits for a response. No checking
     * is done to ensure that the response is associated with the query (other
     * than checking that the DNS packet IDs are equal, and that the IP address
     * which sent the response is the IP address the query was sent to) The QID
     * of the Message which is sent will be the QID of the Message which is
     * returned.
     *
     * @param query The query to send.
     * @return The response.
     * @throws IOException An error occurred while sending or receiving.
     */
    @Override
    public Message send(Message query) throws IOException {

        ResponseQueue queue = new ResponseQueue();
        Object id = sendAsync(query, queue);
        Response response = queue.getItem();
        if (response.getId() != id) {
            throw new IllegalStateException("Wrong id (" + response.getId()
                    + ", should be " + id + ") returned from sendAsync()!");
        }
        if (response.isException()) {
            if (response.getException() instanceof SocketTimeoutException) {
                throw new SocketTimeoutException(response.getException().getMessage());
            } else if (response.getException() instanceof IOException) {
                throw (IOException) (response.getException());
            } else {
                throw new IllegalStateException("Unexpected exception!\r\n"
                        + response.getException().toString());
            }
        }
        return response.getMessage();
    }

    /**
     * Old-style interface
     *
     * @param message message to send
     * @param resolverListener object to call back
     * @return id of the query
     */
    @Override
    public Object sendAsync(Message message, ResolverListener resolverListener) {
        // If this method is called, then the Transaction should fire up a new
        // thread, and use it to
        // call the client back.
        // If not this method, then the Transaction should use the standard
        // behaviour of inserting
        // the response in to the client-supplied ResponseQueue.
        final Object id;
        synchronized (this) {
            id = new Integer(uniqueID++);
        }
        sendAsync(message, id, resolverListener);
        return id;
    }

    /**
     * Old-style interface
     *
     * @param message message to send
     * @param resolverListener object to call back
     */
    @Override
    public void sendAsync(Message message, Object id,
            ResolverListener resolverListener) {
        sendAsync(message, id, timeoutValue, useTCP, null, false,
                resolverListener);
    }

    /**
     * Asynchronously sends a message to a single nameserver, registering a
     * ResponseQueue to buffer responses on success or exception. Multiple
     * asynchronous lookups can be performed in parallel.
     *
     * @param query The query to send
     * @param responseQueue the queue for the responses
     * @return An identifier, which is also a data member of the Response
     */
    @Override
    public Object sendAsync(final Message query, final ResponseQueue responseQueue) {
        final Object id;
        synchronized (this) {
            id = new Integer(uniqueID++);
        }
        sendAsync(query, id, responseQueue);
        return id;
    }

    /**
     * Add the query to the queue for the NonblockingResolverEngine
     *
     * @param query The query to send
     * @param id The object to be used as the id in the callback
     * @param responseQueue The queue for the responses
     */
    @Override
    public void sendAsync(final Message query, Object id,
            final ResponseQueue responseQueue) {
        sendAsync(query, id, timeoutValue, useTCP, responseQueue);
    }

    @Override
    public void sendAsync(final Message inQuery, Object id, int inQueryTimeout,
            boolean queryUseTCP, final ResponseQueue responseQueue) {
        sendAsync(inQuery, id, inQueryTimeout, queryUseTCP, responseQueue,
                true, null);
    }

    private void sendAsync(final Message inQuery, Object id,
            int inQueryTimeout, boolean queryUseTCP,
            final ResponseQueue responseQueue, boolean useResponseQueue,
            ResolverListener listener) {

        if (LOG.isTraceEnabled()) {
            LOG.trace("sendAsync(id=" + id + ")");
            LOG.trace(inQuery);
        }

        if (!useResponseQueue && (listener == null)) {
            throw new IllegalArgumentException(
                    "No ResolverListener supplied for callback when useResponsequeue = true!");
        }

        if (Options.check("verbose")) {
            LOG.info(MessageFormat.format("Sending to {0}, from {1}", remoteAddress.getAddress(), remoteAddress.getAddress()));
        }

        if (inQuery.getHeader().getOpcode() == Opcode.QUERY) {
            Record question = inQuery.getQuestion();
            if (question != null && question.getType() == Type.AXFR) {
                throw new UnsupportedOperationException(
                        "AXFR not implemented in NonblockingResolver");
            }
        }

        int queryTimeout = inQueryTimeout;
        Message query = (Message) inQuery.clone();
        applyEDNS(query);
        if (tsig != null) {
            tsig.apply(query, null);
        }

        byte[] out = query.toWire(Message.MAXLENGTH);
        int udpSize = maxUDPSize(query);
        boolean tcp = false;
        long endTime = System.currentTimeMillis() + queryTimeout;

        if (queryUseTCP || out.length > udpSize) {
            tcp = true;
        }

        // Send the query to the nioEngine.
        // If !useResponseQueue, then the Transaction should fire up a new
        // thread, and use it to
        // call the client back.
        // If useResponseQueue, then the Transaction should use the standard
        // behaviour of inserting
        // the response in to the client-supplied ResponseQueue.
        // Use SinglePortTransactionController if possible, otherwise get new
        // Transaction.
        int qid = query.getHeader().getID();
        if (useSingleTCPPort
                && tcp
                && transactionController.headerIdNotInUse(qid)) {
            QueryData qData = new QueryData();
            qData.setTcp(tcp);
            qData.setIgnoreTruncation(ignoreTruncation);
            qData.setTsig(tsig);
            qData.setQuery(query);
//                    if (!tcp) {
//                        qData.setUdpSize(udpSize);
//                    }
            if (useResponseQueue) {
                transactionController.sendQuery(qData, id, responseQueue, endTime);
            } else {
                // Start up the Transaction with a ResolverListener
                transactionController.sendQuery(qData, id, listener, endTime);
            }
        } else if (useSingleUDPPort
                && !tcp
                && transactionController.headerIdNotInUse(qid)) {
            QueryData qData = new QueryData();
            qData.setTcp(false);
            qData.setIgnoreTruncation(ignoreTruncation);
            qData.setTsig(tsig);
            qData.setQuery(query);
            qData.setUdpSize(udpSize);

            if (useResponseQueue) {
                transactionController.sendQuery(qData, id, responseQueue, endTime);
            } else {
                // Start up the Transaction with a ResolverListener
                transactionController.sendQuery(qData, id, listener, endTime);
            }
        } else {
            // Pick a random port here - don't leave it to the OS!
            InetSocketAddress localAddr = getNewInetSocketAddressWithRandomPort(localAddress.getAddress());

            Transaction transaction = new Transaction(remoteAddress, localAddr,
                    tsig, tcp, ignoreTruncation);
            if (!tcp) {
                transaction.setUdpSize(udpSize);
            }
            if (useResponseQueue) {
                transaction.sendQuery(query, id, responseQueue, endTime);
            } else {
                // Start up the Transaction with a ResolverListener
                transaction.sendQuery(query, id, listener, endTime);
            }
        }
    }

    public static InetSocketAddress getNewInetSocketAddressWithRandomPort(InetAddress addr) {
        int portNum = 1024 + random.nextInt(65535 - 1024);
        InetSocketAddress localAddr = new InetSocketAddress(addr, portNum);
        return localAddr;
    }

    // private Message
    // sendAXFR(Message query) throws IOException {
    // Name qname = query.getQuestion().getName();
    // ZoneTransferIn xfrin = ZoneTransferIn.newAXFR(qname, remoteAddress,
    // tsig);
    // try {
    // xfrin.run();
    // }
    // catch (ZoneTransferException e) {
    // throw new WireParseException(e.getMessage());
    // }
    // List records = xfrin.getAXFR();
    // Message response = new Message(query.getHeader().getID());
    // response.getHeader().setFlag(Flags.AA);
    // response.getHeader().setFlag(Flags.QR);
    // response.addRecord(query.getQuestion(), Section.QUESTION);
    // Iterator it = records.iterator();
    // while (it.hasNext())
    // response.addRecord((Record)it.next(), Section.ANSWER);
    // return response;
    // }
    public static Message parseMessage(byte[] b) throws WireParseException {
        try {
            return (new Message(b));
        } catch (IOException e) {
            if (Options.check("verbose")) {
                e.printStackTrace(System.err);
            }
            if (!(e instanceof WireParseException)) {
                e = new WireParseException("Error parsing message");
            }
            throw (WireParseException) e;
        }
    }

    public static void verifyTSIG(Message query, Message response, byte[] b,
            TSIG tsig) {
        if (tsig == null) {
            return;
        }
        int error = tsig.verify(response, b, query.getTSIG());
//		if (error == Rcode.NOERROR)
//			response.tsigState = Message.TSIG_VERIFIED;
//		else
//			response.tsigState = Message.TSIG_FAILED;
        if (Options.check("verbose")) {
            LOG.info("TSIG verify: " + Rcode.string(error));
        }
    }

    /**
     * Called by the Connection to check if the data received so far constitutes
     * a complete packet.
     *
     * @param in
     * @return true if the packet is complete
     */
    public static boolean isDataComplete(byte[] in) {
        // Match up the returned qData with the QueryDataList
        // Make sure that the header ID is correct for the header in qData
        // If it matches qData, that's great.
        // If it doesn't, then we need to find the queryData which *does* match
        // it.
        // We then close that connection.
        try {
            if (in.length < Header.LENGTH) {
                return false;
            }
            Message message = parseMessage(in);
            int messLen = message.numBytes();
            boolean ready = (messLen == in.length);
            return (ready);
        } catch (IOException e) {
            return false;
        }
    }
}
