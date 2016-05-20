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

import java.util.LinkedList;

/**
 * This class implements a simple FIFO queue. It blocks threads wishing to
 * remove an object from the queue until an object is available.
 */
public class ResponseQueue {

    protected LinkedList<Response> list = new LinkedList<>();
    protected int waitingThreads = 0;

    /**
     * This method is called to add a new {@link Response} to the queue. The
     * {@link Response} is added to the bottom of the queue.
     *
     * @param response the new {@code Response} to add to queue.
     */
    public synchronized void insert(Response response) {
        list.addLast(response);
        notify();
    }

    /**
     * This method blocks while the queue is empty. It will return the first
     * item in the queue when available.
     *
     * @return A {@code Response} from the top of the queue.
     */
    public synchronized Response getItem() {
        while (isEmpty()) {
            try {
                waitingThreads++;
                wait();
            } catch (InterruptedException e) {
            }
            waitingThreads--;
        }
        return list.removeFirst();
    }

    /**
     * Determines if the queue is empty based on the number of elements in the
     * queue - the number of waiting {@code Thread}s.
     *
     * @return {@code false} if the (queue size - the number of waiting threads)
     * is greater than zero.{@code true} if the value is value is less than or
     * equal to zero.
     */
    public boolean isEmpty() {
        return (list.size() - waitingThreads <= 0);
    }
}
