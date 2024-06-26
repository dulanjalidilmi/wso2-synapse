/**
 *  Copyright (c) 2009, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.synapse.transport.passthru;

import org.apache.axiom.om.OMOutputFormat;
import org.apache.axiom.soap.SOAP11Constants;
import org.apache.axiom.soap.SOAP12Constants;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.nio.ContentEncoder;
import org.apache.http.nio.NHttpServerConnection;
import org.apache.http.params.DefaultedHttpParams;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.transport.passthru.config.SourceConfiguration;
import org.apache.synapse.transport.passthru.util.PassThroughTransportUtils;
import org.apache.synapse.transport.passthru.util.RelayUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


public class SourceResponse {
    private Pipe pipe = null;
    /** Transport headers */
    private Map<String, TreeSet<String>> headers = new HashMap<String, TreeSet<String>>();
    /** Status of the response */
    private int status = HttpStatus.SC_OK;
    /** Status line */
    private String statusLine = null;
    /** Actual response submitted */
    private HttpResponse response = null;
    /** Configuration of the receiver */
    private SourceConfiguration sourceConfiguration;
    /** Version of the response */
    private ProtocolVersion version = HttpVersion.HTTP_1_1;
    /** Connection strategy */
    private ConnectionReuseStrategy connStrategy = new DefaultConnectionReuseStrategy();
    /** Chunk response or not */
    // private boolean chunk = true;
    /** response has an entity or not**/
    private boolean hasEntity = true;
    /** Keep alive request */
    private boolean keepAlive = true;    
    private SourceRequest request = null;
    
    /** If version change required default HTTP 1.1 will be overridden*/
    private boolean versionChangeRequired =false;

    public SourceResponse(SourceConfiguration config, int status, SourceRequest request) {
        this(config, status, null, request);
    }

    public SourceResponse(SourceConfiguration config, int status, String statusLine,
                          SourceRequest request) {
        this.status = status;
        this.statusLine = statusLine;
        this.sourceConfiguration = config;
        this.request = request;
        if (request != null && request.getVersion() != null) {
            this.version = request.getVersion();
        }
    }

    public void connect(Pipe pipe) {
        this.pipe = pipe;

        if (request != null && pipe != null) {
            SourceContext.get(request.getConnection()).setWriter(pipe);
        }
    }

    /**
     * Starts the response by writing the headers
     * @param conn connection
     * @throws java.io.IOException if an error occurs
     * @throws org.apache.http.HttpException if an error occurs
     */
    public void start(NHttpServerConnection conn) throws IOException, HttpException {
        // create the response
        response = sourceConfiguration.getResponseFactory().newHttpResponse(
                request.getVersion(), this.status,
                request.getConnection().getContext());

        if (statusLine != null) {
            if (versionChangeRequired) {
                response.setStatusLine(version, status, statusLine);
            } else {
                response.setStatusLine(request.getVersion(), status, statusLine);
            }
        } else if (versionChangeRequired){
        	response.setStatusLine(version, status);
        } else {
            response.setStatusCode(status);
        }

        BasicHttpEntity entity = null;

        if (canResponseHaveBody(request.getRequest(), response)) {
            entity = new BasicHttpEntity();

            long contentLength = -1;
            String contentLengthHeader;
            for (String header : headers.keySet()) {
                if (HTTP.CONTENT_LEN.equalsIgnoreCase(header)) {
                    contentLengthHeader = headers.get(header).first();
                    contentLength = Long.parseLong(contentLengthHeader);
                    headers.remove(header);
                    break;
                }
            }

            if (contentLength != -1) {
                entity.setChunked(false);
                entity.setContentLength(contentLength);
            } else {
                entity.setChunked(true);
            }

        }
        
        response.setEntity(entity);

        // set any transport headers
        Set<Map.Entry<String, TreeSet<String>>> entries = headers.entrySet();

        for (Map.Entry<String, TreeSet<String>> entry : entries) {
            if (entry.getKey() != null) {     
            	Iterator<String> i = entry.getValue().iterator();
                while(i.hasNext()) {
                	response.addHeader(entry.getKey(), i.next());
                }   
            }
        }
		if (!keepAlive) {
			response.setHeader(HTTP.CONN_DIRECTIVE, HTTP.CONN_CLOSE);
		}  
        response.setParams(new DefaultedHttpParams(response.getParams(),
                                                   sourceConfiguration.getHttpParams()));

        SourceContext.updateState(conn, ProtocolState.RESPONSE_HEAD);

        // Pre-process HTTP response
        conn.getContext().setAttribute(ExecutionContext.HTTP_CONNECTION, conn);
        conn.getContext().setAttribute(ExecutionContext.HTTP_RESPONSE, response);
        conn.getContext().setAttribute(ExecutionContext.HTTP_REQUEST,
                SourceContext.getRequest(conn).getRequest());
        
        sourceConfiguration.getHttpProcessor().process(response, conn.getContext());

        //Since HTTP HEAD request doesn't contain message body, entity will be null when above HttpProcessor()
        // process invoked. Hence content length is set to 0 inside the HTTP-Core. To avoid that content length of
        // the backend response is set as the content length.
        if (entity == null &&
            PassThroughConstants.HTTP_HEAD.equalsIgnoreCase(request.getRequest().getRequestLine().getMethod())) {
            if (response.getFirstHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH) == null && response.getFirstHeader(
		            HTTP.CONTENT_LEN) != null && (response.getFirstHeader(HTTP.CONTENT_LEN).getValue().equals("0"))) {
                response.removeHeaders(HTTP.CONTENT_LEN);
            } else if (response.getFirstHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH) != null) {
                response.removeHeaders(HTTP.CONTENT_LEN);
                response.addHeader(HTTP.CONTENT_LEN,
                                   response.getFirstHeader(PassThroughConstants.ORGINAL_CONTEN_LENGTH).getValue());
                response.removeHeaders(PassThroughConstants.ORGINAL_CONTEN_LENGTH);
            }
        }
        
        conn.submitResponse(response);

        // Handle non entity body responses
        if (entity == null) {
            hasEntity = false;
            // Reset connection state
            sourceConfiguration.getSourceConnections().releaseConnection(conn);
            // Make ready to deal with a new request
            conn.requestInput();
        }
    }

	public void checkResponseChunkDisable(MessageContext responseMsgContext) throws IOException {
        if (responseMsgContext.getProperty(PassThroughConstants.HTTP_SC) != null){
            if (this.canResponseHaveContentLength(responseMsgContext)) {
                calculateContentlengthForChunckDisabledResponse(responseMsgContext);
            }
        } else {
            calculateContentlengthForChunckDisabledResponse(responseMsgContext);
        }
	}

    /**
     * Calculates the content-length when chunking is disabled.
     * @param responseMsgContext outflow message context
     * @throws IOException
     */
    private void calculateContentlengthForChunckDisabledResponse(MessageContext responseMsgContext) throws IOException {
        String forceHttp10 = (String) responseMsgContext.getProperty(PassThroughConstants.FORCE_HTTP_1_0);
        boolean isChunkingDisabled = responseMsgContext.isPropertyTrue(PassThroughConstants.DISABLE_CHUNKING, false);

        if ("true".equals(forceHttp10) || isChunkingDisabled) {
            if (!responseMsgContext.isPropertyTrue(PassThroughConstants.MESSAGE_BUILDER_INVOKED,
                    false)) {
                try {
                    RelayUtils.buildMessage(responseMsgContext, false);
                    responseMsgContext.getEnvelope().buildWithAttachments();
                } catch (Exception e) {
                    throw new AxisFault(e.getMessage());
                }
            }

            if("true".equals(forceHttp10)){
                version = HttpVersion.HTTP_1_0;
                versionChangeRequired=true;
            }

            Boolean noEntityBody =
                    (Boolean) responseMsgContext.getProperty(PassThroughConstants.NO_ENTITY_BODY);

            if (noEntityBody != null && Boolean.TRUE == noEntityBody) {
                headers.remove(HTTP.CONTENT_TYPE);
                return;
            }

            String contentType = headers.get(HTTP.CONTENT_TYPE) != null ?
                    headers.get(HTTP.CONTENT_TYPE).toString() : null;
            // Stream should be preserved to support disable chunking for SOAP based responses. This checks
            // whether chunking is disabled and response Content-Type is SOAP or FORCE_HTTP_1.0 is true.
            boolean preserveStream =
                    (isChunkingDisabled && isSOAPContentType(contentType)) || "true".equals(forceHttp10);

            MessageFormatter formatter = MessageProcessorSelector.getMessageFormatter(responseMsgContext);
            OMOutputFormat format = PassThroughTransportUtils.getOMOutputFormat(responseMsgContext);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            formatter.writeTo(responseMsgContext, format, out, preserveStream);
            TreeSet<String> header = new TreeSet<String>();
            header.add(String.valueOf(out.toByteArray().length));
            headers.put(HTTP.CONTENT_LEN, header);
        }
    }

    /**
     * Consume the content through the Pipe and write them to the wire
     *
     * @param conn    connection
     * @param encoder encoder
     * @return number of bytes written
     * @throws java.io.IOException if an error occurs
     */
    public int write(NHttpServerConnection conn, ContentEncoder encoder) throws IOException {
        int bytes = 0;
        if (pipe != null) {
            bytes = pipe.consume(encoder);
        } else {
            encoder.complete();
        }
        // Update connection state
        writePostActions(conn, encoder);
        return bytes;
    }

    /**
     * Same as
     * {@link SourceResponse#write(org.apache.http.nio.NHttpServerConnection, org.apache.http.nio.ContentEncoder)}
     * but gives out the data consumed through the Pipe
     *
     * @param conn    connection
     * @param encoder encoder
     * @return data consumed
     * @throws java.io.IOException if an error occurs
     */
    public ByteBuffer copyAndWrite(NHttpServerConnection conn, ContentEncoder encoder) throws IOException {

        ByteBuffer bytes = null;
        if (pipe != null) {
            bytes = pipe.copyAndConsume(encoder);
        } else {
            encoder.complete();
        }
        writePostActions(conn, encoder);
        return bytes;
    }

    private void writePostActions(NHttpServerConnection conn, ContentEncoder encoder) {

        if (encoder.isCompleted()) {
            SourceContext.updateState(conn, ProtocolState.RESPONSE_DONE);

            sourceConfiguration.getMetrics().
                    notifySentMessageSize(conn.getMetrics().getSentBytesCount());

            if (SourceContext.get(conn).isShutDown()) {
                // we need to shut down if the shutdown flag is set
                SourceContext.updateState(conn, ProtocolState.CLOSING);
                sourceConfiguration.getSourceConnections().closeConnection(conn, true);
            } else if (response != null && !this.connStrategy.keepAlive(response, conn.getContext())) {
                SourceContext.updateState(conn, ProtocolState.CLOSING);
                sourceConfiguration.getSourceConnections().closeConnection(conn);
            } else {
                // Reset connection state
                sourceConfiguration.getSourceConnections().releaseConnection(conn);
                // Ready to deal with a new request                
                conn.requestInput();
            }
        }
    }

    public void addHeader(String name, String value) {
    	if(headers.get(name) == null) {
    		TreeSet<String> values = new TreeSet<String>(); 
    		values.add(value);
    		headers.put(name, values);
    	} else {
    		TreeSet<String> values = headers.get(name);
    		values.add(value);
    	}
    }

    public void setStatus(int status) {
        this.status = status;
    }
    public void removeHeader(String name) {
        if (headers.get(name) != null) {
            headers.remove(name);
        }
    }

    public String getHeader(String name) {
        if (headers.containsKey(name)){
            return headers.get(name).first();
        }
        return null;
    }

    private boolean canResponseHaveBody(final HttpRequest request, final HttpResponse response) {
        if (request != null && "HEAD".equalsIgnoreCase(request.getRequestLine().getMethod())) {
            return false;
        }
        int status = response.getStatusLine().getStatusCode();
        return status >= HttpStatus.SC_OK
               && status != HttpStatus.SC_NO_CONTENT
               && status != HttpStatus.SC_NOT_MODIFIED
               && status != HttpStatus.SC_RESET_CONTENT;
    }

    /**
     * Checks whether response can have Content-Length header
     * @param responseMsgContext out flow message context
     * @return true if response can have Content-Length header else false
     */
    private boolean canResponseHaveContentLength(MessageContext responseMsgContext) {
        Object httpStatus = responseMsgContext.getProperty(PassThroughConstants.HTTP_SC);
        int status;
        if (httpStatus == null || httpStatus.toString().equals("")) {
            return false;
        }
        if (httpStatus instanceof String) {
            status = Integer.parseInt((String)httpStatus);
        } else {
            status = (Integer) httpStatus;
        }
        if (request != null && PassThroughConstants.HTTP_CONNECT.equals(request.getRequest().getRequestLine()
                                                                                .getMethod())) {
            return (status / 100 != 2);
        } else {
            return HttpStatus.SC_NO_CONTENT != status && (status / 100 != 1);
        }
    }

    public boolean hasEntity() {
       return this.hasEntity;
    }

	public void setKeepAlive(boolean keepAlive) {
		this.keepAlive = keepAlive;
	}

    /**
     * Checks whether Content-Type header related to a SOAP message
     *
     * @param contentType Content-Type string
     * @return true if Content-Type is related to Soap.
     */
    private boolean isSOAPContentType(String contentType) {
        return contentType != null &&
                (contentType.indexOf(SOAP11Constants.SOAP_11_CONTENT_TYPE) != -1 ||
                contentType.indexOf(SOAP12Constants.SOAP_12_CONTENT_TYPE) != -1);
    }

}
