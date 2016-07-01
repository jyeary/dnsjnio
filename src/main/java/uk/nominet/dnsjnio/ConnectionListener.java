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

import java.util.EventListener;

/**
 * Interface specifying callbacks from Connection.
 */
public interface ConnectionListener extends EventListener {

    public void readyToSend(Connection connection);

    public void closed(Connection connection);

    public void dataAvailable(byte[] data, Connection connection);

    public int getPort();
}
