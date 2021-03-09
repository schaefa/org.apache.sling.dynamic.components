package org.apache.sling.dynamic.aem;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.dynamic.common.DynamicComponentFilterNotifier;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.WriteListener;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.apache.sling.dynamic.common.Constants.SLING_RESOURCE_SUPER_TYPE_PROPERTY;

@Component(
    name = "Dynamic Component Filter",
    immediate = true,
    property = {
        Constants.SERVICE_DESCRIPTION + "=" + "Filter to add Dynamic Components to the Components List",
        "service.ranking" + "=" + "100",
        "sling.filter.pattern" + "=" + "/libs/wcm/core/content/components.*",
        "sling.filter.scope" + "=" + "COMPONENT"
    }
)
public class DynamicComponentFilter
    implements Filter, DynamicComponentFilterNotifier
{
    public static final Logger LOGGER = LoggerFactory.getLogger(DynamicComponentFilter.class);

    private Map<String,PropertyHierarchy> dynamicToProvideComponents = new HashMap<>();

    public DynamicComponentFilter() {
        LOGGER.info("DC Filter created");
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        LOGGER.info("DC Filter Initialized: '{}'", filterConfig);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        LOGGER.info("DC Filter called");
        // Wrap the Response to catch the output so that we can later add out part to the output
        if (request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse) {
            // Only support JSon request
            final SlingHttpServletRequest httpRequest = (SlingHttpServletRequest) request;
            final SlingHttpServletResponse httpResponse = (SlingHttpServletResponse) response;
            RequestPathInfo requestPathInfo = httpRequest.getRequestPathInfo();
            if(
                requestPathInfo.getResourcePath().equals("/libs/wcm/core/content/components") &&
                requestPathInfo.getExtension().equalsIgnoreCase("json")
            ) {
                final SpoolServletOutputStream outputStream = new SpoolServletOutputStream();
                final SlingHttpServletResponseWrapper wrapper = new SlingHttpServletResponseWrapper(httpResponse) {
                    @Override
                    public ServletOutputStream getOutputStream() throws IOException {
                        LOGGER.info("Get Output Stream: '{}'", outputStream);
                        return outputStream;
                    }

                    @Override
                    public PrintWriter getWriter() throws IOException {
                        LOGGER.info("Get Output Writer: '{}'", outputStream.getWriter());
                        return outputStream.getWriter();
                    }

                    @Override
                    public void setContentLength(int len) {
                        // Ignore this
                        LOGGER.info("Ignored Content Length set to: {}", len);
//                        super.setContentLength(len);
                    }

                    @Override
                    public void setContentLengthLong(long len) {
                        // Ignore this
                        LOGGER.info("Ignored Content Length Long set to: {}", len);
//                        super.setContentLengthLong(len);
                    }
                };
                chain.doFilter(request, wrapper);
                // Get the Output, parse it into JSon
                byte[] bytes = outputStream.toByteArray();
                String content = decompress(bytes);
                char last = content.charAt(content.length() - 1);
                if(last == '}') {
                    content = content.substring(0, content.length() - 1)
                        + compileDynamicComponentsOutput(httpRequest.getResourceResolver())
                        + "}";
                    LOGGER.info("Content after Filter Chain: '{}'", content);
                    byte[] output = compress(content);
                    LOGGER.info("Compress Length: '{}'", output.length);
                    response.getOutputStream().write(output);
                    response.flushBuffer();
                } else {
                    LOGGER.info("Last Character is unexpected: '{}'", last);
                    response.getOutputStream().write(content.getBytes());
                }
            } else {
                chain.doFilter(request, response);
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    private String compileDynamicComponentsOutput(ResourceResolver resourceResolver) {
        String answer = "";
        for(Entry<String,PropertyHierarchy> entry: dynamicToProvideComponents.entrySet()) {
            String component = "";
            String dynamicPath = entry.getKey();
            int index = dynamicPath.indexOf('/', 1);
            if(index <= 0) {
                LOGGER.warn("Dynamic Path is not valid: '{}'", dynamicPath);
                continue;
            }
            component = "\"" + dynamicPath.substring(index + 1) + "\": {"
                + "\"path\":\"" + dynamicPath + "\"";
//            Resource provided = resourceResolver.getResource(entry.getValue());
            PropertyHierarchy propertyHierarchy = entry.getValue();
//            ValueMap propertyHierarchy = provided.getValueMap();
            if(propertyHierarchy.containsKey("componentGroup")) {
                component += ",\"group\":\"" + propertyHierarchy.get("componentGroup", "weird-group") + "\"";
            }
            if(propertyHierarchy.containsKey("jcr:title")) {
                component += ",\"title\":\"" + propertyHierarchy.get("jcr:title", "weird-title") + "\"";
            }
            component += ",\"resourceType\":\"" + dynamicPath.substring(index + 1) + "\"";
            if(propertyHierarchy.containsKey("cq:icon")) {
                component += ",\"iconName\":\"" + propertyHierarchy.get("cq:icon", "weird-icon") + "\"";
            }
            component += "}";
            answer += "," + component;
        }
        return answer;
    }

    @Override
    public void destroy() {

    }

    @Override
    public void addDynamicComponent(String dynamicComponentPath, Resource providedComponent) {
        PropertyHierarchy propertyHierarchy = new PropertyHierarchy(providedComponent);
        dynamicToProvideComponents.put(dynamicComponentPath, propertyHierarchy);
    }

    @Override
    public void removeDynamicComponent(String dynamicComponentPath) {
        dynamicToProvideComponents.remove(dynamicComponentPath);
    }

    private String decompress(byte[] content) throws IOException {
        try (
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(content));
        ) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipInputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            baos.flush();
            return baos.toString();
        }
    }

    private byte[] compress(String content) throws IOException {
        try (
            ByteArrayOutputStream boas = new ByteArrayOutputStream();
            GZIPOutputStream gzipOutputStream = new GZIPOutputStream(boas);
        ) {
            byte[] bytes = content.getBytes();
            int test = content.getBytes().length;
            gzipOutputStream.write(bytes, 0, bytes.length);
            LOGGER.info("Content Length: {}", test);
            gzipOutputStream.close();
            return boas.toByteArray();
        }
    }

    public static class SpoolServletOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream output;
        private final PrintWriter writer;

        public SpoolServletOutputStream()
            throws UnsupportedEncodingException
        {
            this.output = new ByteArrayOutputStream();
            this.writer = new PrintWriter(new OutputStreamWriter(output, "UTF-8"));
        }

        final PrintWriter getWriter() {
            return this.writer;
        }

        @Override
        public void write(int b) throws IOException {
            this.output.write(b);
        }

        final byte[] toByteArray() {
            this.writer.flush();
            byte[] bytes = output.toByteArray();
            return bytes;
        }

        public final String toContent() {
            this.writer.flush();
            try {
                return this.output.toString("UTF-8");
            } catch (UnsupportedEncodingException e) {
                return "";
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // Ignore for now
        }
    }

    private static class PropertyHierarchy {
//        private Resource source;
        private String providedComponentPath;
        private Map<String, Object> hierarchicalProperties = new HashMap<>();

        public PropertyHierarchy(Resource source) {
//            this.source = resource;
            providedComponentPath = source.getPath();
            String[] searchPaths = source.getResourceResolver().getSearchPath();
            LOGGER.info("Search Paths: '{}'", Arrays.asList(searchPaths));
            traverse(source, searchPaths);
        }

        public String getProvidedComponentPath() {
            return providedComponentPath;
        }

        public boolean containsKey(String propertyName) {
            return hierarchicalProperties.containsKey(propertyName);
        }

        public String get(String propertyName) {
            if(hierarchicalProperties.containsKey(propertyName)) {
                Object temp = hierarchicalProperties.get(propertyName);
                return temp == null ? "" : temp.toString();
            } else {
                return null;
            }
        }

        public String get(String propertyName, String defaultValue) {
            if(hierarchicalProperties.containsKey(propertyName)) {
                Object temp = hierarchicalProperties.get(propertyName);
                return temp == null ? "" : temp.toString();
            } else {
                return defaultValue;
            }
        }

        private void traverse(Resource resource, String[] searchPaths) {
            LOGGER.info("Traverse resource: '{}'", resource);
            ValueMap properties = resource.getValueMap();
            if(properties != null) {
                for(Entry<String,Object> entry: properties.entrySet()) {
                    if(!hierarchicalProperties.containsKey(entry.getKey())) {
                        hierarchicalProperties.put(entry.getKey(), entry.getValue());
                    }
                }
                String superType = properties.get(SLING_RESOURCE_SUPER_TYPE_PROPERTY, String.class);
                if(superType != null) {
                    LOGGER.info("Super Type found: '{}'", superType);
                    Resource superResource = null;
                    if(!superType.startsWith("/")) {
                        for(String searchPath: searchPaths) {
                            String path = searchPath + superType;
                            superResource = resource.getResourceResolver().getResource(path);
                            if(superResource != null) {
                                LOGGER.info("Found Super Type ('{}'): '{}'", path, superResource);
                                break;
                            }
                        }
                    } else {
                        superResource = resource.getResourceResolver().getResource(superType);
                    }
                    if(superResource != null) {
                        traverse(superResource, searchPaths);
                    }
                }
            }
        }
    }
}