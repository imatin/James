/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.imapserver.netty;

import org.apache.james.imap.decode.DecodingException;
import org.apache.james.imap.decode.ImapRequestLineReader;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;

public abstract class AbstractNettyImapRequestLineReader extends ImapRequestLineReader {
    private Channel channel;
    private ChannelBuffer cRequest = ChannelBuffers.wrappedBuffer("+\r\n".getBytes());
    private boolean retry;

    public AbstractNettyImapRequestLineReader(Channel channel, boolean retry) {
        this.channel = channel;
        this.retry = retry;

    }

    /**
     * @see
     * org.apache.james.imap.decode.ImapRequestLineReader#commandContinuationRequest()
     */
    protected void commandContinuationRequest() throws DecodingException {
        // only write the request out if this is not a retry to process the
        // request..

        if (!retry)
            channel.write(cRequest);
    }

}
