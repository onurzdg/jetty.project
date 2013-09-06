//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.fcgi.FCGI;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class ServerGenerator extends Generator
{
    private static final byte[] STATUS = new byte[]{'S', 't', 'a', 't', 'u', 's'};
    private static final byte[] COLON = new byte[]{':', ' '};
    private static final byte[] EOL = new byte[]{'\r', '\n'};

    public ServerGenerator(ByteBufferPool byteBufferPool)
    {
        super(byteBufferPool);
    }

    public Result generateResponseHeaders(int request, int code, String reason, HttpFields fields, Callback callback)
    {
        request &= 0xFF_FF;

        Charset utf8 = Charset.forName("UTF-8");
        List<byte[]> bytes = new ArrayList<>(fields.size() * 2);
        int length = 0;

        // Special 'Status' header
        bytes.add(STATUS);
        length += STATUS.length + COLON.length;
        if (reason == null)
            reason = HttpStatus.getMessage(code);
        byte[] responseBytes = (code + " " + reason).getBytes(utf8);
        bytes.add(responseBytes);
        length += responseBytes.length + EOL.length;

        // Other headers
        for (HttpField field : fields)
        {
            String name = field.getName();
            byte[] nameBytes = name.getBytes(utf8);
            bytes.add(nameBytes);

            String value = field.getValue();
            byte[] valueBytes = value.getBytes(utf8);
            bytes.add(valueBytes);

            length += nameBytes.length + COLON.length;
            length += valueBytes.length + EOL.length;
        }
        // End of headers
        length += EOL.length;

        final ByteBuffer buffer = byteBufferPool.acquire(length, true);
        BufferUtil.clearToFill(buffer);

        for (int i = 0; i < bytes.size(); i += 2)
            buffer.put(bytes.get(i)).put(COLON).put(bytes.get(i + 1)).put(EOL);
        buffer.put(EOL);

        buffer.flip();

        return new Result(generateContent(request, buffer, false, callback, FCGI.FrameType.STDOUT))
        {
            @Override
            protected void recycle()
            {
                super.recycle();
                byteBufferPool.release(buffer);
            }
        };
    }

    public Result generateResponseContent(int request, ByteBuffer content, boolean lastContent, Callback callback)
    {
        Result result = generateContent(request, content, lastContent, callback, FCGI.FrameType.STDOUT);
        if (lastContent)
        {
            // Generate the FCGI_END_REQUEST
            request &= 0xFF_FF;
            ByteBuffer endRequestBuffer = byteBufferPool.acquire(8, false);
            BufferUtil.clearToFill(endRequestBuffer);
            endRequestBuffer.putInt(0x01_03_00_00 + request);
            endRequestBuffer.putInt(0x00_08_00_00);
            endRequestBuffer.putLong(0x00L);
            endRequestBuffer.flip();
            result.add(endRequestBuffer, true);
        }
        return result;
    }
}
