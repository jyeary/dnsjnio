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

import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import org.apache.log4j.Logger;

/**
 * Single port UDP connection. The connection is set to be non-blocking, and
 * {@code SO_REUSEADDR} is set to reuse the port.
 * 
 * @author Alex Dalitz <alex@caerkettontech.com>
 * @author John Yeary <jyeary@bluelotussoftware.com>
 * @author Allan O'Driscoll
 */
public class SinglePortUDPConnection extends UDPConnection {

    private static final Logger LOG = Logger.getLogger(Connection.class);

    public SinglePortUDPConnection(ConnectionListener listener, int port) {
        super(listener, SINGLE_PORT_BUFFER_SIZE);
    }

    @Override
    protected void connect() {
        try {
            DatagramChannel sch = DatagramChannel.open();
            sch.configureBlocking(false);
            sch.socket().setReuseAddress(true);
            sch.socket().bind(localAddress);
            InetSocketAddress addr = (InetSocketAddress) sch.getLocalAddress();
            localPort = addr.getPort();

            if (LOG.isTraceEnabled()) {
                LOG.trace("UDP connection bound to port " + localPort);
            }

            sk = sch.register(DnsController.getSelector(), 0);
            sch.connect(remoteAddress);
            attach(sk);
        } catch (Exception e) {
            LOG.error("Exception while connecting socket on port " + localPort, e);
            close();
        }
    }
}
