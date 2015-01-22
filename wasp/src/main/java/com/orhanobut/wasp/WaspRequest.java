package com.orhanobut.wasp;

import com.orhanobut.wasp.http.Body;
import com.orhanobut.wasp.http.BodyMap;
import com.orhanobut.wasp.http.Header;
import com.orhanobut.wasp.http.Path;
import com.orhanobut.wasp.http.Query;
import com.orhanobut.wasp.http.RetryPolicy;
import com.orhanobut.wasp.http.Tag;
import com.orhanobut.wasp.parsers.Parser;
import com.orhanobut.wasp.utils.AuthToken;
import com.orhanobut.wasp.utils.RequestInterceptor;
import com.orhanobut.wasp.utils.CollectionUtils;
import com.orhanobut.wasp.utils.WaspRetryPolicy;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author Orhan Obut
 */
final class WaspRequest {

    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;
    private final WaspRetryPolicy retryPolicy;
    private final WaspMock mock;
    private final MethodInfo methodInfo;
    private final String tag;

    private WaspRequest(Builder builder) {
        this.url = builder.getUrl();
        this.method = builder.getHttpMethod();
        this.headers = builder.getHeaders();
        this.body = builder.getBody();
        this.retryPolicy = builder.getRetryPolicy();
        this.mock = builder.getMock();
        this.methodInfo = builder.getMethodInfo();
        this.tag = builder.getTag();
    }

    String getUrl() {
        return url;
    }

    String getMethod() {
        return method;
    }

    Map<String, String> getHeaders() {
        return headers != null ? headers : Collections.<String, String>emptyMap();
    }

    String getBody() {
        return body;
    }

    WaspMock getMock() {
        return mock;
    }

    WaspRetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    String getTag() {
        return tag;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Request URL : ").append(url);
        if (body != null) {
            builder.append(", Body: ").append(body);
        }
        if (!getHeaders().isEmpty()) {
            //TODO add header output
        }
        return builder.toString();
    }

    public MethodInfo getMethodInfo() {
        return methodInfo;
    }

    static class Builder {

        private static final String KEY_AUTH = "Authorization";

        private final MethodInfo methodInfo;
        private final String baseUrl;
        private final Object[] args;
        private final Parser parser;

        private String body;
        private String relativeUrl;
        private WaspRetryPolicy retryPolicy;
        private StringBuilder queryParamBuilder;
        private Map<String, String> headers;
        private RequestInterceptor requestInterceptor;

        private String tag;

        Builder(MethodInfo methodInfo, Object[] args, String baseUrl, Parser parser) {
            this.methodInfo = methodInfo;
            this.baseUrl = baseUrl;
            this.args = args;
            this.parser = parser;
            this.relativeUrl = methodInfo.getRelativeUrl();
            this.tag = null;

            initParams();
        }

        private void initParams() {
            Annotation[] annotations = methodInfo.getMethodAnnotations();
            int count = annotations.length;

            for (int i = 0; i < count; i++) {
                Object value = args[i];
                if (value == null) {
                    throw new NullPointerException("Value cannot be null");
                }
                Annotation annotation = annotations[i];
                if (annotation == null) {
                    continue;
                }
                Class<? extends Annotation> annotationType = annotation.annotationType();
                if (annotationType == Path.class) {
                    String key = ((Path) annotation).value();
                    addPathParam(key, String.valueOf(value));
                    continue;
                }
                if (annotationType == Query.class) {
                    String key = ((Query) annotation).value();
                    addQueryParam(key, value);
                    continue;
                }
                if (annotationType == Header.class) {
                    String key = ((Header) annotation).value();
                    addHeaderParam(key, (String) value);
                    continue;
                }
                if (annotationType == Body.class) {
                    body = getBody(value);
                }

                if (annotationType == Tag.class) {
                    tag = (String) value;
                }

                if (annotationType == BodyMap.class) {
                    if (!(value instanceof Map)) {
                        throw new IllegalArgumentException("BodyMap accepts only Map instances");
                    }
                    Map<String, Object> map;
                    try {
                        map = (Map<String, Object>) value;
                    } catch (Exception e) {
                        throw new ClassCastException("Map type should be Map<String,Object>");
                    }
                    body = CollectionUtils.toJson(map);
                }
            }
        }

        Builder setRequestInterceptor(RequestInterceptor interceptor) {
            this.requestInterceptor = interceptor;
            return this;
        }

        /**
         * Merges static and param headers and create a request.
         *
         * @return WaspRequest
         */
        WaspRequest build() {
            postInit();
            return new WaspRequest(this);
        }

        /**
         * It is called right before building a request, this method will add
         * intercepted headers, params, static headers and retry policy
         */
        private void postInit() {
            //Add static headers
            for (Map.Entry<String, String> entry : methodInfo.getHeaders().entrySet()) {
                addHeaderParam(entry.getKey(), entry.getValue());
            }

            //Set retry policy
            if (methodInfo.getRetryPolicy() != null) {
                retryPolicy = methodInfo.getRetryPolicy();
            }

            if (requestInterceptor == null) {
                return;
            }

            //Add intercepted query params
            Map<String, Object> tempQueryParams = new HashMap<>();
            requestInterceptor.onQueryParamsAdded(tempQueryParams);
            for (Map.Entry<String, Object> entry : tempQueryParams.entrySet()) {
                addQueryParam(entry.getKey(), entry.getValue());
            }

            //Add intercepted headers
            Map<String, String> tempHeaders = new HashMap<>();
            requestInterceptor.onHeadersAdded(tempHeaders);
            for (Map.Entry<String, String> entry : tempHeaders.entrySet()) {
                addHeaderParam(entry.getKey(), entry.getValue());
            }

            //If retry policy is not already set via annotations than set it via requestInterceptor
            WaspRetryPolicy waspRetryPolicy = requestInterceptor.getRetryPolicy();
            if (retryPolicy == null && waspRetryPolicy != null) {
                retryPolicy = waspRetryPolicy;
            }

            // If authToken is set, it will check if the filter is enabled
            // it will add token to each request if the filter is not enabled
            // If the filter is enabled, it will be added to request which has @Auth annotation
            AuthToken authToken = requestInterceptor.getAuthToken();
            if (authToken != null) {
                String token = authToken.getToken();
                if (!authToken.isFilterEnabled()) {
                    addHeaderParam(KEY_AUTH, token);
                    return;
                }
                if (methodInfo.isAuthTokenEnabled()) {
                    addHeaderParam(KEY_AUTH, token);
                }
            }
        }

        private String getBody(Object body) {
            return parser.toJson(body);
        }

        /**
         * If endpoint is set as annotation, it uses that endpoint for the call, otherwise it uses endpoint
         *
         * @return full url
         */
        private String getUrl() {
            String endpoint = methodInfo.getBaseUrl();
            if (endpoint == null) {
                endpoint = baseUrl;
            }
            return endpoint + relativeUrl + getQueryString();
        }

        private String getQueryString() {
            if (queryParamBuilder == null) {
                return "";
            }
            return queryParamBuilder.toString();
        }

        private void addPathParam(String key, String value) {
            relativeUrl = relativeUrl.replace("{" + key + "}", value);
        }

        private void addQueryParam(String key, Object value) {
            StringBuilder builder = this.queryParamBuilder;
            if (queryParamBuilder == null) {
                this.queryParamBuilder = builder = new StringBuilder();
            }
            builder.append(queryParamBuilder.length() == 0 ? "?" : "&");
            builder.append(key).append("=").append(value);
        }

        private void addHeaderParam(String key, String value) {
            Map<String, String> headers = this.headers;
            if (headers == null) {
                this.headers = headers = new LinkedHashMap<>();
            }
            headers.put(key, value);
        }

        String getHttpMethod() {
            return methodInfo.getHttpMethod();
        }

        Map<String, String> getHeaders() {
            return headers;
        }

        String getBody() {
            return body;
        }

        WaspRetryPolicy getRetryPolicy() {
            return retryPolicy;
        }

        WaspMock getMock() {
            return methodInfo.getMock();
        }

        MethodInfo getMethodInfo() {
            return methodInfo;
        }

        public String getTag() {
            return tag;
        }
    }
}
