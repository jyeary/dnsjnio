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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import org.apache.log4j.Logger;
import org.xbill.DNS.Message;
import static uk.nominet.dnsjnio.NonblockingResolver.getNewInetSocketAddressWithRandomPort;

/**
 * This class implements the UDP specific methods for the Connection superclass.
 *
 * @author Alex Dalitz <alex@caerkettontech.com>
 * @author John Yeary <jyeary@bluelotussoftware.com>
 * @author Allan O'Driscoll
 */
public class UDPConnection extends Connection {

    private static final Logger LOG = Logger.getLogger(UDPConnection.class);

    public UDPConnection(ConnectionListener listener, int udpSize) {
        super(listener, udpSize);
    }

    @Override
    protected void connect() {
        try {
            DatagramChannel sch = DatagramChannel.open();
            sch.configureBlocking(false);

            // Pick up SocketException here, and keep rebinding to different random port
            boolean connectedOk = false;
            while (!connectedOk) {
                try {
                    sch.socket().bind(localAddress);
                    connectedOk = true;
                    InetSocketAddress addr = (InetSocketAddress) sch.getLocalAddress();
                    localPort = addr.getPort();
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("UDP connection bound to port " + localPort);
                    }
                } catch (java.net.SocketException e) {
                    LOG.trace("UDPConnection exception in connect for port" + localPort, e);
                    // Failure may be caused by picking a port number that was
                    // already in use. Pick another random port and try again.
                    // Note that the socket channel is now invalid, we need to
                    // close it and open a fresh one.
                    localAddress = getNewInetSocketAddressWithRandomPort(localAddress.getAddress());
                    sch.close();
                    sch = DatagramChannel.open();
                    sch.configureBlocking(false);
                }
            }
            sk = sch.register(DnsController.getSelector(), 0);
            sch.connect(remoteAddress);
            attach(sk);
        } catch (Exception e) {
            LOG.error("Exception while connecting UDPConnection for port" + localPort, e);
            close();
        }
    }

    /**
     * Attach key and channel and set connection interest in selection key
     *
     * @param sk The {@link SelectionKey} to attach.
     */
    public void attach(SelectionKey sk) {
        this.sk = sk;
        sk.attach(this);
        DatagramChannel sch = (DatagramChannel) sk.channel();
        if (sch.isConnected()) {
            sk.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            setState(State.OPENED);
        }
    }

    /**
     * <p>
     * Process a connect complete selection.</p>
     * <p>
     * <strong>Note:</strong> This is a NO-OP. Nothing to do for UDP</p>
     */
    @Override
    public void doConnect() {
        //NO-OP 
    }

    /**
     * Process a read ready selection
     */
    @Override
    public void doRead() {

        // Make sure that the IP we're receiving from is the IP we sent to!
        // This is done by the DatagramChannel, which only receives datagrams
        // from the peer it is connected with.
        DatagramChannel sc = (DatagramChannel) sk.channel();
        try {
            readFromChannel(sc);
        } catch (NullPointerException e) {
            return;
        }

        // It's possible that we received more than one DNS packet.
        // Let's split them out, and send each to the client.
        while (recvCount > 0) {
            if (recvBytes != null) {
                byte[] packet = new byte[recvCount];
                System.arraycopy(recvBytes, 0, packet, 0, recvCount);
                try {
                    // Get the first packet in the buffer
                    Message m = new Message(packet);
                    if (m.numBytes() < recvCount) {
                        packet = new byte[m.numBytes()];
                        System.arraycopy(recvBytes, 0, packet, 0, m.numBytes());
                    }
                    sendToUser(packet); // try to send to user
                    // Now clear the buffer
                    clearRecvBytes(packet.length);
                } catch (IOException e) {
                    break;
                }
            }
        }
    }

    /**
     * Write out a byte buffer
     *
     * @param data The data to write to the socket.
     */
    @Override
    protected void write(ByteBuffer data) {
        DatagramChannel sc = (DatagramChannel) sk.channel();
        if (sc.isOpen()) {
            if (data.hasRemaining()) {
                try {
                    int len = sc.write(data);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("write(" + len + " bytes) to port " + localPort);
                    }
                } catch (IOException e) {
                    closeComplete();
                }
            }
            commonEndWrite(data);
        }
    }

    /**
     * Close the connection and its socket
     *
     * @return {@code true} if the socket was successfully closed, and
     * {@code false} otherwise.
     */
    @Override
    protected boolean close() {
        boolean didClose = false;
        if ((getState() != State.CLOSED) && (getState() != State.CLOSING)) {
            // Fix for 20080801 bug reported by :
            // Allan O'Driscoll for sporadic NullPointerException - thanks, Allan!
            if (sk != null) {
                DatagramChannel sc = (DatagramChannel) sk.channel();
                if (sc != null && sc.isOpen()) {
                    didClose = true;
                    if (getState() == State.OPENED) {
                        sk.interestOps(0);
                        setState(State.CLOSING);
                        try {
                            InetSocketAddress addr = (InetSocketAddress) sc.getLocalAddress();
                            if (addr != null && LOG.isTraceEnabled()) {
                                LOG.trace("close() Closing Datagram Socket for port " + localPort);
                            }
                            sc.close();
                        } catch (IOException e) {
                            LOG.error("Exception while closing UDPConnection with port " + localPort, e);
                            //     log error
                        }
                    }
                    closeComplete();
                }
            }
        }
        return didClose;
    }

    @Override
    protected void closeChannel() throws IOException {
        DatagramChannel sc = (DatagramChannel) sk.channel();
        if (sc != null && sc.isOpen()) {
            InetSocketAddress addr = (InetSocketAddress) sc.getLocalAddress();
            if (addr != null && LOG.isTraceEnabled()) {
                LOG.trace("closeChannel() Closing Datagram Socket for port " + localPort);
            }
            sc.close();
        }
    }
}
