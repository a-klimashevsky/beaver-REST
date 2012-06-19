package by.of.beaverrest;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.net.ssl.SSLException;

import org.apache.http.ConnectionReuseStrategy;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ConnectionKeepAliveStrategy;
import org.apache.http.conn.params.ConnManagerParams;
import org.apache.http.conn.params.ConnPerRoute;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONObject;

public class Request {
	public enum HttpMethod{GET,POST,PUT,DELETE}
	
    private static HttpClient client = null;
    
    private static final int DEFAULT_TIMEOUT_MILLIS = 30000;
	
	public synchronized static JSONObject doRequest(String host,String path,HttpMethod method,Map<String,Object> params){
		HttpUriRequest req = null;
        String target = null;

        if (method == HttpMethod.GET) {
            target = buildURL(host, path, params);
            req = new HttpGet(target);
        } else {
            target = buildURL(host, path, null);
            HttpPost post = new HttpPost(target);

            if (params != null && params.size() >= 0) {

                List<NameValuePair> nvps = new ArrayList<NameValuePair>();
                for(Entry<String,Object> param : params.entrySet()){
                	if (param.getValue() != null) {
                        nvps.add(new BasicNameValuePair(param.getKey(), param.getKey()));
                    }
                }
                try {
                    post.setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
                } catch (UnsupportedEncodingException e) {
                    throw new DropboxException(e);
                }
            }

            req = post;
        }

        HttpResponse resp = execute(req);

		return null;
	} 
	
	public static HttpResponse execute(HttpUriRequest req) {
        HttpClient client = getHttpClient();
        
        try {
            HttpResponse response = null;
            for (int retries = 0; response == null && retries < 5; retries++) {
                /*
                 * The try/catch is a workaround for a bug in the HttpClient
                 * libraries. It should be returning null instead when an
                 * error occurs. Fixed in HttpClient 4.1, but we're stuck with
                 * this for now. See:
                 * http://code.google.com/p/android/issues/detail?id=5255
                 */
                try {
                    response = client.execute(req);
                } catch (NullPointerException e) {
                }

                /*
                 * We've potentially connected to a different network, but are
                 * still using the old proxy settings. Refresh proxy settings
                 * so that we can retry this request.
                 */
                if (response == null) {
                    updateClientProxy(client, session);
                }
            }

            if (response == null) {
                // This is from that bug, and retrying hasn't fixed it.
                throw new DropboxIOException("Apache HTTPClient encountered an error. No response, try again.");
            } else if (response.getStatusLine().getStatusCode() != DropboxServerException._200_OK) {
                // This will throw the right thing: either a DropboxServerException or a DropboxProxyException
                parseAsJSON(response);
            }

            return response;
        } catch (SSLException e) {
            throw new DropboxSSLException(e);
        } catch (IOException e) {
            // Quite common for network going up & down or the request being
            // cancelled, so don't worry about logging this
            throw new DropboxIOException(e);
        } catch (OutOfMemoryError e) {
            throw new DropboxException(e);
        }
    }
	
	private static HttpClient getHttpClient() {
        if (client == null) {
            // Set up default connection params. There are two routes to
            // Dropbox - api server and content server.
            HttpParams connParams = new BasicHttpParams();
            ConnManagerParams.setMaxConnectionsPerRoute(connParams, new ConnPerRoute() {
                
                public int getMaxForRoute(HttpRoute route) {
                    return 10;
                }
            });
            ConnManagerParams.setMaxTotalConnections(connParams, 20);

            // Set up scheme registry.
            SchemeRegistry schemeRegistry = new SchemeRegistry();
            schemeRegistry.register(
                    new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
            schemeRegistry.register(
                    new Scheme("https", SSLSocketFactory.getSocketFactory(), 443));
/*
            DBClientConnManager cm = new DBClientConnManager(connParams,
                    schemeRegistry);*/

            // Set up client params.
            HttpParams httpParams = new BasicHttpParams();
            HttpConnectionParams.setConnectionTimeout(httpParams, DEFAULT_TIMEOUT_MILLIS);
            HttpConnectionParams.setSoTimeout(httpParams, DEFAULT_TIMEOUT_MILLIS);
            HttpConnectionParams.setSocketBufferSize(httpParams, 8192);
            HttpProtocolParams.setUserAgent(httpParams,
                    "OfficialDropboxJavaSDK");

            DefaultHttpClient c = new DefaultHttpClient(httpParams) {
                @Override
                protected ConnectionKeepAliveStrategy createConnectionKeepAliveStrategy() {
                    return new DBKeepAliveStrategy();
                }

                @Override
                protected ConnectionReuseStrategy createConnectionReuseStrategy() {
                    return new DBConnectionReuseStrategy();
                }
            };

            c.addRequestInterceptor(new HttpRequestInterceptor() {
                @Override
                public void process(
                        final HttpRequest request, final HttpContext context)
                        throws HttpException, IOException {
                    if (!request.containsHeader("Accept-Encoding")) {
                        request.addHeader("Accept-Encoding", "gzip");
                    }
                }
            });

            c.addResponseInterceptor(new HttpResponseInterceptor() {
                @Override
                public void process(
                        final HttpResponse response, final HttpContext context)
                        throws HttpException, IOException {
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        Header ceheader = entity.getContentEncoding();
                        if (ceheader != null) {
                            HeaderElement[] codecs = ceheader.getElements();
                            for (HeaderElement codec : codecs) {
                                if (codec.getName().equalsIgnoreCase("gzip")) {
                                    response.setEntity(
                                            new GzipDecompressingEntity(response.getEntity()));
                                    return;
                                }
                            }
                        }
                    }
                }
            });

            client = c;
        }

        return client;
    }

	public static String buildURL(String host, String target, Map<String,Object> params) {
        if (!target.startsWith("/")) {
            target = "/" + target;
        }

        try {
            // We have to encode the whole line, then remove + and / encoding
            // to get a good OAuth URL.
            target = URLEncoder.encode("/" + target, "UTF-8");
            target = target.replace("%2F", "/");

            if (params != null && params.size() > 0) {
                target += "?" + urlencode(params);
            }

            // These substitutions must be made to keep OAuth happy.
            target = target.replace("+", "%20").replace("*", "%2A");
        } catch (UnsupportedEncodingException uce) {
            return null;
        }

        return "https://" + host + ":443" + target;
    }
	
	 /**
     * URL encodes an array of parameters into a query string.
     */
    private static String urlencode(Map<String,Object> params) {
        String result = "";
        try {
            boolean firstTime = true;
            for(Entry<String,Object> param : params.entrySet()){
            	if (firstTime) {
                    firstTime = false;
                } else {
                    result += "&";
                }
            	result += URLEncoder.encode(param.getKey(), "UTF-8") + "="
                + URLEncoder.encode(param.getValue().toString(), "UTF-8");
            }

            result.replace("*", "%2A");
        } catch (UnsupportedEncodingException e) {
            return null;
        }
        return result;
    }
}
