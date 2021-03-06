/*
Copyright 2007 Nominet UK

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
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import junit.framework.TestCase;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import org.xbill.DNS.*;

// Although we need one end-to-end test,
// these test should mainly run against our own server.
// that way, tests aren't affected by connectivity, broken server, etc.
// We need to use a real resolver for the 500 concurrent query test, though...
public class ServerTest extends TestCase {

    final static String SERVER = "localhost";

    final static int PORT = TestServer.PORT;

    // final static int PORT = 53;
    final static int TIMEOUT = 10;

    private int idCount = 0;

    private int headerIdCount = 0;

    static TestServer server = TestServer.startServer();

    @Override
    public void setUp() {
        reset();
        // TestServer.startServer();
    }

    @Override
    public void finalize() {
        server.stopRunning();
    }

    private void reset() {
        idCount = 0;
        Timer.reset();
    }

    private Message getQuery(String nameString) throws TextParseException {
        Name name = Name.fromString(nameString, Name.root);
        Record question = Record.newRecord(name, Type.A, DClass.ANY);
        return Message.newQuery(question);
    }

    public void testSendAsync() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setPort(PORT);
        resolver.setTimeout(TIMEOUT);
        Name name = Name.fromString("example.net", Name.root);
        Record question = Record.newRecord(name, Type.A, DClass.ANY);
        Message query = Message.newQuery(question);
        Integer id = idCount++;
        ResponseQueue queue = new ResponseQueue();
        resolver.sendAsync(query, id, queue);
        Response result = queue.getItem();
        assertTrue("Exception thrown " + result.getException(), !result
                .isException());
        assertTrue(result.getMessage().getRcode() == 0);
        assertTrue(result.getId() == id);
    }

    public void testTimeoutsAsync() throws Exception {
        // Start a query which will never end
        // wait for the required timeout period
        NonblockingResolver resolver = new NonblockingResolver("localhost");
        resolver.setPort(PORT);
        resolver.setTimeout(TIMEOUT);
        Message query = getQuery("timeout.example.net");
        Integer id = idCount++;
        ResponseQueue queue = new ResponseQueue();
        resolver.sendAsync(query, id, queue);
        Response result = queue.getItem();
        assertTrue(result.getId() == id);
        if (!result.isException()) {
            fail("Response received for impossible query!");
        }
        if (result.getException() == null) {
            fail("No exception thrown when timeout expected!");
        }
        if (!(result.getException() instanceof SocketTimeoutException)) {
            fail(MessageFormat.format("Exception {0} thrown instead of SocketTimeoutException!", result.getException()));
        }
    }

    public void testManySequentialAsynchronousRequests() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setPort(PORT);
        doTestManySequentialAsynchronousRequests(resolver);
    }

    private void doTestManySequentialAsynchronousRequests(
            NonblockingResolver resolver) throws TextParseException {
        int numRequests = 50;
        String name = "example.net";
        resolver.setTimeout(TIMEOUT);
        int bad = 0;
        for (int i = 0; i < numRequests; i++) {
            Message query = getQuery(name + idCount);
            Integer id = idCount++;
            ResponseQueue queue = new ResponseQueue();
            resolver.sendAsync(query, id, queue);
            Response result = queue.getItem();
            assertTrue(result != null);
            // System.out.println("Got back " + i + ", " +
            // (result.getException() instanceof SocketTimeoutException ? "timed
            // out" : "OK"));
            if (result.isException() || result.getException() != null) {
                bad++;
            }
        }
        assertTrue("Too many exceptions! (" + bad + " of " + numRequests + ")",
                bad < (numRequests * 0.05));
    }

    public void testManyAsynchronousRequests() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setPort(PORT);
        doTestManyAsynchronousRequests(resolver);
    }

    private void doTestManyAsynchronousRequests(NonblockingResolver resolver)
            throws Exception {
        doTestManyAsynchronousRequests(resolver, false);
    }

    private void doTestManyAsynchronousRequests(NonblockingResolver resolver,
            boolean useSameHeaderId) throws Exception {
        int numRequests = 250;
        if (resolver.isTCP()) {
            numRequests = 50;
        }
        String name = "example.net";
        // String name = RemoteServerTest.REAL_QUERY_NAME;
        resolver.setTimeout(TIMEOUT);
        int startId = idCount;
        int bad = 0;
        ResponseQueue queue = new ResponseQueue();
        for (int i = 0; i < numRequests; i++) {
            Message query = getQuery(name);
            int headerId = headerIdCount;
            if (!useSameHeaderId) {
                headerId = headerIdCount++;
            }
            query.getHeader().setID(headerId);
            Integer id = idCount++;
            resolver.sendAsync(query, id, queue);
        }
        for (int i = startId; i < startId + numRequests; i++) {
            Response response = queue.getItem();
            if (!response.isException()) {
                // System.out.println(i + ", Result " + response.getId() + "
                // received OK");
            } else {
                // System.out.println(i+ ", Result " + response.getId() + "
                // threw Exception " + response.getException());
            }
            assertTrue(!response.isException()
                    || response.getException() != null);
            if (response.isException()) {
                bad++;
            }
        }
        assertTrue("Too many exceptions! (" + bad + " of " + numRequests + ")",
                bad < (numRequests * 0.1));
    }

    ResponseQueue queue = new ResponseQueue();

    public void testManyAsynchronousClients() throws Exception {
        // test many NonblockingResolvers using asynchronous sends
        int numClients = 100;
        int bad = 0;
        for (int i = 0; i < numClients; i++) {
            Thread task = new Thread() {
                @Override
                public void run() {
                    try {
                        NonblockingResolver resolver = new NonblockingResolver(
                                SERVER);
                        resolver.setPort(PORT);
                        resolver.setTimeout(TIMEOUT);
                        Object id = idCount++;
                        Message query = getQuery("example"
                                + id + ".net");
                        resolver.sendAsync(query, id, queue);
                    } catch (UnknownHostException | TextParseException e) {
                    }
                }
            };
            task.start();
        }

        for (int i = 0; i < numClients; i++) {
            Response response = queue.getItem();
            if (!response.isException()) {
                // System.out.println("Result " + response.getId() + " received
                // OK");
                // int port =
                // PortTest.getPortFromResponse(response.getMessage());
                // System.out.println("Response received from port " + port);
            } else {
                // System.out.println("Result " + response.getId() + " threw
                // Exception " + response.getException());
            }
            assertTrue(!response.isException()
                    || response.getException() != null);
            if (response.isException()) {
                bad++;
            }
        }
        assertTrue("Too many exceptions! (" + bad + " of " + numClients + ")",
                bad < (numClients * 0.05));
    }

    public void testManySequentialSynchronousRequests() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        doTestManySequentialSynchronousRequests(resolver);
    }

    private void doTestManySequentialSynchronousRequests(
            NonblockingResolver resolver) throws TextParseException {
        int numRequests = 50;
        resolver.setTimeout(TIMEOUT);
        resolver.setPort(PORT);
        int bad = 0;
        for (int i = 0; i < numRequests; i++) {
            String name = "example" + i + ".net.";
            Message query = getQuery(name);
            try {
                Message ret = resolver.send(query);
                if (!ret.getQuestion().getName().toString().equals(name)) {
                    fail("Name should be " + name + " but is "
                            + ret.getQuestion().getName().toString());
                }
                // System.out.println(i + " returned OK");
            } catch (Exception e) {
                if (!(e instanceof SocketTimeoutException)) {
                    e.printStackTrace(System.err);
                    fail();
                } else {
                    // System.out.println(i + " timed out");
                }
                bad++;
            }
        }
        assertTrue("Too many exceptions! (" + bad + " of " + numRequests + ")",
                bad < (numRequests * 0.05));
    }

    public void testSend() throws Exception {
        Resolver resolver = new NonblockingResolver(SERVER);
        resolver.setTimeout(TIMEOUT);
        resolver.setPort(PORT);

        Name name = Name.fromString("example.net", Name.root);
        Record question = Record.newRecord(name, Type.A, DClass.ANY);
        Message query = Message.newQuery(question);
        Message response = resolver.send(query);
        assertTrue(response.getRcode() == Rcode.NOERROR);
    }

    public void testTimeoutSync() throws Exception {
        final int timeout = 0;
        Resolver resolver = new NonblockingResolver(SERVER);
        resolver.setPort(PORT);
        resolver.setTimeout(timeout);
        Message query = getQuery("timeout.net");
        try {
            Message result = resolver.send(query);
            fail("Exception expected, but got " + result);
        } catch (IOException e) {
            assertTrue(e instanceof SocketTimeoutException);
        }
    }

    public void testQueryTimeout() throws Exception {
        // Test that the query sets timeout values correctly
        // To test the timeout, set the Resolver timeout to be 100, and the
        // query timeout to be 1.
        NonblockingResolver resolver = new NonblockingResolver("localhost");
        resolver.setPort(PORT);
        resolver.setTimeout(TIMEOUT);
        Message query = getQuery("timeout.example.net");
        Integer id = idCount++;
        ResponseQueue queue = new ResponseQueue();
        long startTime = System.currentTimeMillis();
        resolver.sendAsync(query, id, 1, false, queue);
        Response result = queue.getItem();
        assertTrue(result.getId() == id);
        long endTime = System.currentTimeMillis();
        long time = startTime - endTime;
        assertTrue(time < 100); // Check that query timeout of 1 was used, not
        // resolver timeout of 100
        if (!result.isException()) {
            fail("Response recevied for impossible query!");
        }
        if (result.getException() == null) {
            fail("No exception thrown when timeout expected!");
        }
        if (!(result.getException() instanceof SocketTimeoutException)) {
            fail(MessageFormat.format("Exception {0} thrown instead of SocketTimeoutException!", result.getException()));
        }
    }

    public void testTCP() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setTimeout(TIMEOUT);
        resolver.setPort(PORT);
        Name name = Name.fromString("example.net", Name.root);
        Record question = Record.newRecord(name, Type.A, DClass.ANY);
        Message query = Message.newQuery(question);
        resolver.setTCP(true);
        Message response = resolver.send(query);
        assertTrue(response.getRcode() == Rcode.NOERROR);
    }

    public void testManySequentialAsynchronousRequestsTCP() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setPort(PORT);
        resolver.setTCP(true);
        doTestManySequentialAsynchronousRequests(resolver);
    }

    public void testManyAsynchronousRequestsTCP() throws Exception {
        // Our blocking test server is unhappy about handling 500 concurrent
        // requests...
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setTCP(true);
        resolver.setPort(PORT);
        doTestManyAsynchronousRequests(resolver);
    }

    public void testManySequentialSynchronousRequestsTCP() throws Exception {
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setTCP(true);
        doTestManySequentialSynchronousRequests(resolver);
    }

    public void testManyThreadedSynchronousClients() throws Exception {
        int numThreads = 250;
        List threads = new LinkedList();
        int bad = 0;
        for (int i = 0; i < numThreads; i++) {
            SynchronousQueryThread thread = new SynchronousQueryThread(
                    "example" + i + ".net", TIMEOUT, idCount++);
            threads.add(thread);
        }
        for (Iterator it = threads.iterator(); it.hasNext();) {
            SynchronousQueryThread thread = (SynchronousQueryThread) (it.next());
            thread.start();
        }
        for (Iterator it = threads.iterator(); it.hasNext();) {
            SynchronousQueryThread thread = (SynchronousQueryThread) (it.next());
            thread.join();
            if (thread.response != null) {
                // System.out.println("Result " + thread.id + " received OK");
            } else {
                // System.out.println("Result " + thread.id + " threw
                // Exception");
                bad++;
            }
            if (thread.x != null) {
                thread.x.printStackTrace(System.err);
            }
        }
        assertTrue("Too many exceptions! (" + bad + " of " + numThreads + ")",
                bad < (numThreads * 0.05));
    }

    public class SynchronousQueryThread extends Thread {

        String name;

        Object id;

        NonblockingResolver resolver;

        Message query;

        Message response;

        Exception x;

        public SynchronousQueryThread(String name, int timeout, Object id)
                throws Exception {
            this.name = name;
            this.id = id;
            resolver = new NonblockingResolver(SERVER);
            resolver.setTimeout(timeout);
            resolver.setPort(PORT);
            query = getQuery(name);
        }

        @Override
        public void run() {
            try {
                response = resolver.send(query);
            } catch (Exception e) {
                x = e;
            }
        }
    }

    public void testMultipleIdenticalHeaderIds() throws Exception {
        // Send many queries with the same header ID, and check they all work.
        NonblockingResolver resolver = new NonblockingResolver(SERVER);
        resolver.setPort(PORT);
        doTestManyAsynchronousRequests(resolver, true);
    }

}
