/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.cxf.jaxrs;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.Response;

import org.apache.camel.CamelExchangeException;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.cxf.CxfEndpointUtils;
import org.apache.camel.component.cxf.CxfOperationException;
import org.apache.camel.component.cxf.common.message.CxfConstants;
import org.apache.camel.impl.DefaultProducer;
import org.apache.camel.util.IOHelper;
import org.apache.camel.util.LRUSoftCache;
import org.apache.cxf.Bus;
import org.apache.cxf.jaxrs.JAXRSServiceFactoryBean;
import org.apache.cxf.jaxrs.client.Client;
import org.apache.cxf.jaxrs.client.JAXRSClientFactoryBean;
import org.apache.cxf.jaxrs.client.WebClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CxfRsProducer binds a Camel exchange to a CXF exchange, acts as a CXF
 * JAXRS client, it will turn the normal Object invocation to a RESTful request
 * according to resource annotation.  Any response will be bound to Camel exchange.
 */
public class CxfRsProducer extends DefaultProducer {

    private static final Logger LOG = LoggerFactory.getLogger(CxfRsProducer.class);

    private boolean throwException;
    
    // using a cache of factory beans instead of setting the address of a single cfb
    // to avoid concurrent issues
    private ClientFactoryBeanCache clientFactoryBeanCache;
    
    public CxfRsProducer(CxfRsEndpoint endpoint) {
        super(endpoint);
        this.throwException = endpoint.isThrowExceptionOnFailure();
        clientFactoryBeanCache = new ClientFactoryBeanCache(endpoint.getMaxClientCacheSize());
    }
    
    protected void doStart() throws Exception {
        clientFactoryBeanCache.start();
        super.doStart();
    }
    
    protected void doStop() throws Exception {
        super.doStop();
        clientFactoryBeanCache.stop();
    }

    public void process(Exchange exchange) throws Exception {
        Message inMessage = exchange.getIn();
        Boolean httpClientAPI = inMessage.getHeader(CxfConstants.CAMEL_CXF_RS_USING_HTTP_API, Boolean.class);
        // set the value with endpoint's option
        if (httpClientAPI == null) {
            httpClientAPI = ((CxfRsEndpoint) getEndpoint()).isHttpClientAPI();
        }
        if (httpClientAPI.booleanValue()) {
            invokeHttpClient(exchange);
        } else {
            invokeProxyClient(exchange);
        }
    }

    @SuppressWarnings("unchecked")
    protected void invokeHttpClient(Exchange exchange) throws Exception {
        Message inMessage = exchange.getIn();
        JAXRSClientFactoryBean cfb = clientFactoryBeanCache.get(CxfEndpointUtils
            .getEffectiveAddress(exchange, ((CxfRsEndpoint)getEndpoint()).getAddress()));
        Bus bus = ((CxfRsEndpoint)getEndpoint()).getBus();
        // We need to apply the bus setting from the CxfRsEndpoint which is not use the default bus
        if (bus != null) {
            cfb.setBus(bus);
        }
        WebClient client = cfb.createWebClient();
        String httpMethod = inMessage.getHeader(Exchange.HTTP_METHOD, String.class);
        Class<?> responseClass = inMessage.getHeader(CxfConstants.CAMEL_CXF_RS_RESPONSE_CLASS, Class.class);
        Type genericType = inMessage.getHeader(CxfConstants.CAMEL_CXF_RS_RESPONSE_GENERIC_TYPE, Type.class);
        String path = inMessage.getHeader(Exchange.HTTP_PATH, String.class);

        if (LOG.isTraceEnabled()) {
            LOG.trace("HTTP method = {}", httpMethod);
            LOG.trace("path = {}", path);
            LOG.trace("responseClass = {}", responseClass);
        }

        // set the path
        if (path != null) {
            client.path(path);
        }

        CxfRsEndpoint cxfRsEndpoint = (CxfRsEndpoint) getEndpoint();
        // check if there is a query map in the message header
        Map<String, String> maps = inMessage.getHeader(CxfConstants.CAMEL_CXF_RS_QUERY_MAP, Map.class);
        if (maps == null) {
            // Get the map from HTTP_QUERY header
            String queryString = inMessage.getHeader(Exchange.HTTP_QUERY, String.class);
            if (queryString != null) {
                maps = getQueryParametersFromQueryString(queryString,
                                                         IOHelper.getCharsetName(exchange));
            }
        }
        if (maps == null) {
            maps = cxfRsEndpoint.getParameters();
        }
        if (maps != null) {
            for (Map.Entry<String, String> entry : maps.entrySet()) {
                client.query(entry.getKey(), entry.getValue());
            }
        }

        CxfRsBinding binding = cxfRsEndpoint.getBinding();

        // set the body
        Object body = null;
        if (!"GET".equals(httpMethod)) {
            // need to check the request object.           
            body = binding.bindCamelMessageBodyToRequestBody(inMessage, exchange);
            if (LOG.isTraceEnabled()) {
                LOG.trace("Request body = " + body);
            }
        }

        // set headers
        client.headers(binding.bindCamelHeadersToRequestHeaders(inMessage.getHeaders(), exchange));

        // invoke the client
        Object response = null;
        if (responseClass == null || Response.class.equals(responseClass)) {
            response = client.invoke(httpMethod, body);
        } else {
            if (Collection.class.isAssignableFrom(responseClass)) {
                if (genericType instanceof ParameterizedType) {
                    // Get the collection member type first
                    Type[] actualTypeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
                    response = client.invokeAndGetCollection(httpMethod, body, (Class<?>) actualTypeArguments[0]);
                    
                } else {
                    throw new CamelExchangeException("Header " + CxfConstants.CAMEL_CXF_RS_RESPONSE_GENERIC_TYPE + " not found in message", exchange);
                }
            } else {
                response = client.invoke(httpMethod, body, responseClass);
            }
        }
        int statesCode = client.getResponse().getStatus();
        //Throw exception on a response > 207
        //http://en.wikipedia.org/wiki/List_of_HTTP_status_codes
        if (throwException) {
            if (response instanceof Response) {
                Integer respCode = ((Response) response).getStatus();
                if (respCode > 207) {
                    throw populateCxfRsProducerException(exchange, (Response) response, respCode);
                }
            }
        }
        // set response
        if (exchange.getPattern().isOutCapable()) {
            LOG.trace("Response body = {}", response);
            exchange.getOut().getHeaders().putAll(exchange.getIn().getHeaders());
            exchange.getOut().setBody(binding.bindResponseToCamelBody(response, exchange));
            exchange.getOut().getHeaders().putAll(binding.bindResponseHeadersToCamelHeaders(response, exchange));
            exchange.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, statesCode);
        }
    }

    protected void invokeProxyClient(Exchange exchange) throws Exception {
        Message inMessage = exchange.getIn();
        Object[] varValues = inMessage.getHeader(CxfConstants.CAMEL_CXF_RS_VAR_VALUES, Object[].class);
        String methodName = inMessage.getHeader(CxfConstants.OPERATION_NAME, String.class);
        Client target = null;
        
        JAXRSClientFactoryBean cfb = clientFactoryBeanCache.get(CxfEndpointUtils
                                   .getEffectiveAddress(exchange, ((CxfRsEndpoint)getEndpoint()).getAddress()));
        Bus bus = ((CxfRsEndpoint)getEndpoint()).getBus();
        // We need to apply the bus setting from the CxfRsEndpoint which is not use the default bus
        if (bus != null) {
            cfb.setBus(bus);
        }
        if (varValues == null) {
            target = cfb.create();
        } else {
            target = cfb.createWithValues(varValues);
        }
        // find out the method which we want to invoke
        JAXRSServiceFactoryBean sfb = cfb.getServiceFactory();
        sfb.getResourceClasses();
        // check the null body first
        Object[] parameters = null;
        if (inMessage.getBody() != null) {
            parameters = inMessage.getBody(Object[].class);
        }
        // get the method
        Method method = findRightMethod(sfb.getResourceClasses(), methodName, getParameterTypes(parameters));
        // Will send out the message to
        // Need to deal with the sub resource class
        Object response = method.invoke(target, parameters);
        int statesCode = target.getResponse().getStatus();
        if (throwException) {
            if (response instanceof Response) {
                Integer respCode = ((Response) response).getStatus();
                if (respCode > 207) {
                    throw populateCxfRsProducerException(exchange, (Response) response, respCode);
                }
            }
        }
        CxfRsEndpoint cxfRsEndpoint = (CxfRsEndpoint) getEndpoint();
        CxfRsBinding binding = cxfRsEndpoint.getBinding();
        
        if (exchange.getPattern().isOutCapable()) {
            LOG.trace("Response body = {}", response);
            exchange.getOut().getHeaders().putAll(exchange.getIn().getHeaders());
            exchange.getOut().setBody(binding.bindResponseToCamelBody(response, exchange));
            exchange.getOut().getHeaders().putAll(binding.bindResponseHeadersToCamelHeaders(response, exchange));
            exchange.getOut().setHeader(Exchange.HTTP_RESPONSE_CODE, statesCode);
        }
    }
    
    private Map<String, String> getQueryParametersFromQueryString(String queryString, String charset) throws UnsupportedEncodingException {
        Map<String, String> answer  = new LinkedHashMap<String, String>();
        for (String param : queryString.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair.length == 2) {
                String name = URLDecoder.decode(pair[0], charset);
                String value = URLDecoder.decode(pair[1], charset);
                answer.put(name, value);
            } else {
                throw new IllegalArgumentException("Invalid parameter, expected to be a pair but was " + param);
            }
        }
        return answer;
    }

    private Method findRightMethod(List<Class<?>> resourceClasses, String methodName, Class<?>[] parameterTypes) throws NoSuchMethodException {
        Method answer = null;
        for (Class<?> clazz : resourceClasses) {
            try {
                answer = clazz.getMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException ex) {
                // keep looking 
            } catch (SecurityException ex) {
                // keep looking
            }
            if (answer != null) {
                return answer;
            }
        }
        throw new NoSuchMethodException("Cannot find method with name: " + methodName + " having parameters: " + arrayToString(parameterTypes));
    }

    private Class<?>[] getParameterTypes(Object[] objects) {
        // We need to handle the void parameter situation.
        if (objects == null) {
            return new Class[]{};
        }
        Class<?>[] answer = new Class[objects.length];
        int i = 0;
        for (Object obj : objects) {
            answer[i] = obj.getClass();
            i++;
        }
        return answer;
    }

    private String arrayToString(Object[] array) {
        StringBuilder buffer = new StringBuilder("[");
        for (Object obj : array) {
            if (buffer.length() > 2) {
                buffer.append(",");
            }
            buffer.append(obj.toString());
        }
        buffer.append("]");
        return buffer.toString();
    }

    protected CxfOperationException populateCxfRsProducerException(Exchange exchange, Response response, int responseCode) {
        CxfOperationException exception;
        String uri = exchange.getFromEndpoint().getEndpointUri();
        String statusText = Response.Status.fromStatusCode(responseCode).toString();
        Map<String, String> headers = parseResponseHeaders(response, exchange);
        //Get the response detail string
        String copy = exchange.getContext().getTypeConverter().convertTo(String.class, response.getEntity());
        if (responseCode >= 300 && responseCode < 400) {
            String redirectLocation;
            if (response.getMetadata().getFirst("Location") != null) {
                redirectLocation = response.getMetadata().getFirst("location").toString();
                exception = new CxfOperationException(uri, responseCode, statusText, redirectLocation, headers, copy);
            } else {
                //no redirect location
                exception = new CxfOperationException(uri, responseCode, statusText, null, headers, copy);
            }
        } else {
            //internal server error(error code 500)
            exception = new CxfOperationException(uri, responseCode, statusText, null, headers, copy);
        }

        return exception;
    }

    protected Map<String, String> parseResponseHeaders(Object response, Exchange camelExchange) {

        Map<String, String> answer = new HashMap<String, String>();
        if (response instanceof Response) {

            for (Map.Entry<String, List<Object>> entry : ((Response) response).getMetadata().entrySet()) {
                LOG.trace("Parse external header {}={}", entry.getKey(), entry.getValue());
                answer.put(entry.getKey(), entry.getValue().get(0).toString());
            }
        }

        return answer;
    }
    
    /**
     * Cache contains {@link org.apache.cxf.jaxrs.client.JAXRSClientFactoryBean}
     */
    private class ClientFactoryBeanCache {
        private LRUSoftCache<String, JAXRSClientFactoryBean> cache;    
        
        public ClientFactoryBeanCache(final int maxCacheSize) {
            this.cache = new LRUSoftCache<String, JAXRSClientFactoryBean>(maxCacheSize);
        }
        
        public void start() throws Exception {
            cache.resetStatistics();
        }
        
        public void stop() throws Exception {
            cache.clear();
        }

        public JAXRSClientFactoryBean get(String address) throws Exception {
            JAXRSClientFactoryBean retVal = null;
            synchronized (cache) {
                retVal = cache.get(address);
                
                if (retVal == null) {
                    retVal = ((CxfRsEndpoint)getEndpoint()).createJAXRSClientFactoryBean(address);
                    
                    cache.put(address, retVal);
                    
                    LOG.trace("Created client factory bean and add to cache for address '{}'", address);
                    
                } else {
                    LOG.trace("Retrieved client factory bean from cache for address '{}'", address);
                }
            }
            return retVal;
        }
    }
}
