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

import java.io.*;
import junit.framework.TestCase;
import org.xbill.DNS.*;

/**
 * Exercise the ExtendedNonblockingResolver a little
 */
public class ExtendedResolverTest extends TestCase {

    final static String SERVER = "localhost";
    final static int PORT = TestServer.PORT;
    final static int TIMEOUT = 2;
    final static int NUM_SERVERS = 2;
    final static int NUM_REQUESTS = 100;
    TestServer[] servers;
    NonblockingResolver[] resolvers;
    ExtendedNonblockingResolver eres;
    static volatile int headerIdCount = 0;

    private void startServers(int numServers) throws Exception {
        // Start up a load of resolvers on localhost (running on different
        // ports)
        stopServers();
//		System.out.println("Starting servers");
        servers = new TestServer[numServers];
        resolvers = new NonblockingResolver[NUM_SERVERS];
        for (int i = 0; i < numServers; i++) {
            servers[i] = TestServer.startServer(PORT + 1 + i, NUM_REQUESTS + 1,
                    1);
            NonblockingResolver res = new NonblockingResolver(SERVER);
            res.setTimeout(TIMEOUT);
            res.setPort(PORT + 1 + i);
            resolvers[i] = res;
        }
        eres = ExtendedNonblockingResolver.newInstance(resolvers);
        eres.setRetries(1);
    }

    private void stopServers() {
//		System.out.println("Stopping servers");
        if (servers != null) {
            for (TestServer server : servers) {
                server.stopRunning();
            }
        }
        TestServer.stopServer();
    }

    public void testExtendedNonblockingResolver() throws Exception {
        startServers(NUM_SERVERS);

        // Run some tests on these servers where :
        // a) All servers return response (with random time delays)
        runAllGoodTest();
        // b) All servers time out or throw other exception
        runAllBadTest();
        // c) Some servers return response, others throw exceptions
        runSomeGoodTest();
        // d) Now try a synchronous test
        runSynchronousTest();

        // e) Now try a query with some of the servers timing out, and see
        // what query ID we get back.
        runDifferentQidTest();

        stopServers();
    }

    public void runAllGoodTest() throws Exception {
        // Set all servers to return response (with random time delays)
        // Then send the query and make sure it comes back OK.
        System.out.println("runAllGoodTest");
        runMultipleQueries(true);
    }

    public void runAllBadTest() throws Exception {
        // b) All servers time out or throw other exception
        // Set all servers to fail (with random time delays)
        // Then send the query and make sure it throws an exception.
        System.out.println("runAllBadTest");
        runMultipleQueries(false);
    }

    public void runSomeGoodTest() throws Exception {
        // c) Some servers return response, others throw exceptions
        // Set some servers to fail and some to return (with random time delays)
        // Then send the query and make sure it returns.
        System.out.println("runSomeGoodTest");
        runMultipleQueries(true);
    }

    public void runSynchronousTest() throws Exception {
        System.out.println("runSynchronousTest");
        // Try getting a good reply
        Message query = makeQuery();
        eres.send(query);

        // And try getting a timeout
        query = makeTimeoutQuery();
        try {
            eres.send(query);
            assertTrue("Should get a timeout", false);
        } catch (IOException e) {
            // OK
        }
    }

    private Message makeQuery() throws TextParseException {
        Name name = Name.fromString("example.net", Name.root);
        Record question = Record.newRecord(name, Type.A, DClass.ANY);
        Message query = Message.newQuery(question);
        return query;
    }

    private Message makeTimeoutQuery() throws TextParseException {
        Name name;
        Record question;
        Message query;
        name = Name.fromString("timeout.example.net", Name.root);
        question = Record.newRecord(name, Type.A, DClass.ANY);
        query = Message.newQuery(question);
        return query;
    }

    private void runMultipleQueries(boolean expectedOk) throws Exception {
        // Send a load of concurrent queries to the ENBR
        Name name = Name.fromString("example.net", Name.root);
        if (!expectedOk) {
            // Change the name to get the server to time out
            name = Name.fromString("timeout.example.net", Name.root);
        }
        Record question = Record.newRecord(name, Type.A, DClass.ANY);
        Message query = Message.newQuery(question);
        int bad = 0;

        ResponseQueue queue = new ResponseQueue();
        for (int i = 0; i < NUM_REQUESTS; i++) {
            int headerId = headerIdCount;
            headerId = headerIdCount++;
            query.getHeader().setID(headerId);
//			System.out.println("Sending Query " + headerId);
            eres.sendAsync(query, queue);
        }
        for (int i = 0; i < NUM_REQUESTS; i++) {
//			System.out.println("Waiting on next item");
            Response response;
            synchronized (queue) {
                response = queue.getItem();
            }
            if (!response.isException()) {
//				System.out.println(i + ", Result " + response.getId()
//						+ " received OK");
            } else {
//				System.out.println(i + ", Result " + response.getId()
//						+ "threw Exception " + response.getException());
            }
            assertTrue(!response.isException()
                    || response.getException() != null);
            if (response.isException()) {
                bad++;
            }
        }
        if (expectedOk) {
            assertTrue(bad == 0);
        } else {
            assertTrue(bad == NUM_REQUESTS);
        }
    }

    public void runDifferentQidTest() throws Exception {
        // Query for timeout. The first few queries will fail.
        // We want to set the timeout such that query will eventually succeed.
        // We then want to check the QID of the returned query.
        // Can we find out which server it was from?
        eres.setTimeout(0, 50);
        eres.setRetries(10);
        Message query = makeQuery();
        query.getHeader().setID(42);
        long startTime = System.currentTimeMillis();
        Message response = eres.send(query);
        long endTime = System.currentTimeMillis();
        assertTrue("Too short", (endTime - startTime) >= 75);
        int backID = response.getHeader().getID();
        assertTrue("Wrong ID!", backID != 42);
    }
}
