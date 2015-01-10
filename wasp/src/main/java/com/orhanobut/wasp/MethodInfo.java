package com.orhanobut.wasp;

import com.orhanobut.wasp.http.Body;
import com.orhanobut.wasp.http.BodyMap;
import com.orhanobut.wasp.http.Headers;
import com.orhanobut.wasp.http.Header;
import com.orhanobut.wasp.http.Path;
import com.orhanobut.wasp.http.Query;
import com.orhanobut.wasp.http.RestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Orhan Obut
 */
final class MethodInfo {

    private static final int HEAD_VALUE_LENGTH = 2;

    private final Method method;

    private String relativeUrl;
    private String httpMethod;
    private Type responseObjectType;
    private Annotation[] methodAnnotations;
    private Map<String, String> headers;

    private MethodInfo(Method method) {
        this.method = method;
        init();
    }

    synchronized void init() {
        parseMethodAnnotations();
        parseResponseObjectType();
        parseParamAnnotations();
    }

    static MethodInfo newInstance(Method method) {
        return new MethodInfo(method);
    }

    private void parseMethodAnnotations() {
        Annotation[] annotations = method.getAnnotations();
        for (Annotation annotation : annotations) {
            Class<? extends Annotation> annotationType = annotation.annotationType();

            // look for headers
            if (annotationType == Headers.class) {
                String[] headers = ((Headers) annotation).value();
                if (headers == null) {
                    throw new NullPointerException("HEAD value may not be null");
                }
                addHeaders(headers);
                continue;
            }

            RestMethod methodInfo = null;

            // Look for a @RestMethod annotation on the parameter annotation indicating request method.
            for (Annotation innerAnnotation : annotationType.getAnnotations()) {
                if (RestMethod.class == innerAnnotation.annotationType()) {
                    methodInfo = (RestMethod) innerAnnotation;
                    break;
                }
            }
            if (methodInfo == null) {
                throw new NullPointerException("method annotation may not be null");
            }
            String path;
            try {
                path = (String) annotationType.getMethod("value").invoke(annotation);
            } catch (Exception e) {
                throw methodError("Failed to extract String 'value' from @%s annotation.",
                        annotationType.getSimpleName());
            }
            relativeUrl = path;
            httpMethod = methodInfo.value();
        }
    }

    private void addHeaders(String[] values) {
        for (String header : values) {
            String[] strings = header.split(":");
            if (strings.length != HEAD_VALUE_LENGTH) {
                throw new IllegalArgumentException("HEAD value must follow key : value format");
            }
            if (headers == null) {
                headers = new HashMap<>();
            }
            headers.put(strings[0], strings[1]);
        }
    }

    private void parseResponseObjectType() {
        Type[] parameterTypes = method.getGenericParameterTypes();
        if (parameterTypes.length == 0) {
            throw new IllegalArgumentException("Callback should be added as param");
        }
        Type lastArgType;
        Class<?> lastArgClass = null;

        Type typeToCheck = parameterTypes[parameterTypes.length - 1];
        lastArgType = typeToCheck;
        if (typeToCheck instanceof ParameterizedType) {
            typeToCheck = ((ParameterizedType) typeToCheck).getRawType();
        }
        if (typeToCheck instanceof Class) {
            lastArgClass = (Class<?>) typeToCheck;
        }
        if (!CallBack.class.isAssignableFrom(lastArgClass)) {
            throw new IllegalArgumentException("Last param should be CallBack");
        }

        lastArgType = RetroTypes.getSupertype(lastArgType, RetroTypes.getRawType(lastArgType), CallBack.class);
        if (lastArgType instanceof ParameterizedType) {
            responseObjectType = getParameterUpperBound((ParameterizedType) lastArgType);
        }
    }

    private void parseParamAnnotations() {
        Annotation[][] annotationArrays = method.getParameterAnnotations();
        methodAnnotations = new Annotation[annotationArrays.length];

        List<String> pathParams = new ArrayList<>();
        List<String> queryParams = new ArrayList<>();
        List<String> headerParams = new ArrayList<>();
        boolean isBodyAdded = false;

        int count = annotationArrays.length;
        for (int i = 0; i < count; i++) {
            Annotation annotationResult = null;
            for (Annotation annotation : annotationArrays[i]) {
                Class<? extends Annotation> annotationType = annotation.annotationType();
                if (annotationType == Path.class) {
                    //TODO validate
                    String value = ((Path) annotation).value();
                    if (pathParams.contains(value)) {
                        throw new IllegalArgumentException("Path name should not be duplicated");
                    }
                    pathParams.add(value);
                }
                if (annotationType == Body.class) {
                    if (isBodyAdded) {
                        throw new IllegalArgumentException("Only one body/bodyMap can be added");
                    }
                    isBodyAdded = true;
                }
                if (annotationType == BodyMap.class) {
                    if (isBodyAdded) {
                        throw new IllegalArgumentException("Only one body/bodyMap can be added");
                    }
                    isBodyAdded = true;
                }
                if (annotationType == Query.class) {
                    //TODO validate
                    String value = ((Query) annotation).value();
                    if (queryParams.contains(value)) {
                        throw new IllegalArgumentException("Query name should not be duplicated");
                    }
                    queryParams.add(value);
                }
                if (annotationType == Header.class) {
                    String value = ((Header) annotation).value();
                    if (headerParams.contains(value)) {
                        throw new IllegalArgumentException("Header name should not be duplicated");
                    }
                    headerParams.add(value);
                }

                annotationResult = annotation;
            }
            methodAnnotations[i] = annotationResult;
        }
    }

    private static Type getParameterUpperBound(ParameterizedType type) {
        Type[] types = type.getActualTypeArguments();
        for (int i = 0; i < types.length; i++) {
            Type paramType = types[i];
            if (paramType instanceof WildcardType) {
                types[i] = ((WildcardType) paramType).getUpperBounds()[0];
            }
        }
        return types[0];
    }

    private RuntimeException methodError(String message, Object... args) {
        if (args.length > 0) {
            message = String.format(message, args);
        }
        return new IllegalArgumentException(
                method.getDeclaringClass().getSimpleName() + "." + method.getName() + ": " + message);
    }

    String getRelativeUrl() {
        return relativeUrl;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    Type getResponseObjectType() {
        return responseObjectType;
    }

    Annotation[] getMethodAnnotations() {
        return methodAnnotations;
    }

    Map<String, String> getHeaders() {
        return headers != null ? headers : Collections.<String, String>emptyMap();
    }
}
