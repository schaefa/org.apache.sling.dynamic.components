package org.apache.sling.dynamic.aem;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.SlingHttpServletResponseWrapper;
import org.apache.sling.dynamic.common.DynamicComponent;
import org.apache.sling.dynamic.common.DynamicComponentFilterNotifier;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferencePolicyOption;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.apache.sling.dynamic.common.Constants.DYNAMIC_COMPONENTS_SERVICE_USER;

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

    private Map<String,Resource> targetToSourceMap = new HashMap<>();
    private Map<String,DynamicComponentMapper> dynamicToProvideComponents = new HashMap<>();

    private AtomicBoolean changes = new AtomicBoolean(true);
    private String dynamicOutput;

    public DynamicComponentFilter() {
        LOGGER.info("DC Filter created");
    }

    @Reference
    private ResourceResolverFactory resourceResolverFactory;
    // Make sure that the Service User Mapping is available before obtaining the Service Resource Resolver
    @Reference(policyOption= ReferencePolicyOption.GREEDY)
    private ServiceUserMapped serviceUserMapped;

    @Activate
    public void activate() throws LoginException {
        try (ResourceResolver resourceResolver = resourceResolverFactory.getServiceResourceResolver(
            new HashMap<String, Object>() {{ put(ResourceResolverFactory.SUBSERVICE, DYNAMIC_COMPONENTS_SERVICE_USER); }}
        )) {
            DynamicComponentMapper.searchPaths = resourceResolver.getSearchPath();
        }
    }
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        LOGGER.info("DC Filter Initialized: '{}'", filterConfig);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        LOGGER.info("DC Filter called");
        // Wrap the Response to catch the output so that we can later add out part to the output
        if (!dynamicToProvideComponents.isEmpty() && request instanceof SlingHttpServletRequest && response instanceof SlingHttpServletResponse) {
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
                    }
                };
                chain.doFilter(request, wrapper);
                // Get the Output, parse it into JSon
                byte[] bytes = outputStream.toByteArray();
                String content = decompress(bytes);
                char last = content.charAt(content.length() - 1);
                if(last == '}') {
                    if (changes.getAndSet(false)) {
                        dynamicOutput = compileDynamicComponentsOutput();
                    }
                    content = content.substring(0, content.length() - 1)
                        + dynamicOutput
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

    private String compileDynamicComponentsOutput() {
        String answer = "";
        for(Entry<String,DynamicComponentMapper> entry: dynamicToProvideComponents.entrySet()) {
            DynamicComponentMapper dc = entry.getValue();
            String component = dc.mapToJSon();
            if(component.charAt(0) == '{' && component.charAt(component.length() - 1) == '}') {
                answer += "," + component.substring(1, component.length() - 1);
            } else {
                LOGGER.warn("Unexpected JSon Content: '{}'", component);
            }
        }
        return answer;
    }

    @Override
    public void destroy() {

    }

    @Override
    public void addDynamicComponent(String dynamicComponentPath, Resource source) {
        targetToSourceMap.put(dynamicComponentPath, source);
        dynamicToProvideComponents.put(dynamicComponentPath, new DynamicComponentMapper(dynamicComponentPath, source));
        changes.set(true);
    }

    @Override
    public void removeDynamicComponent(String dynamicComponentPath) {
        dynamicToProvideComponents.remove(dynamicComponentPath);
        changes.set(true);
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
            LOGGER.debug("Content Length: {}", test);
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
}