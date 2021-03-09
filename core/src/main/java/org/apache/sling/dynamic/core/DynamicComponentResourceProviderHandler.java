package org.apache.sling.dynamic.core;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.spi.resource.provider.ProviderContext;
import org.apache.sling.spi.resource.provider.ResolveContext;
import org.apache.sling.spi.resource.provider.ResourceContext;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import static org.apache.sling.api.resource.Resource.RESOURCE_TYPE_NON_EXISTING;
import static org.apache.sling.dynamic.core.DynamicComponent.createSyntheticFromResource;
import static org.osgi.framework.Constants.SERVICE_DESCRIPTION;
import static org.osgi.framework.Constants.SERVICE_VENDOR;

/**
 * This a Resource Provider that provides a Dynamic Component that is not available in
 * the given source folder. It will then create a Synthetic Resource that points a component
 * in the provider folder to the source folder.
 */
public class DynamicComponentResourceProviderHandler
    extends ResourceProvider
    implements DynamicComponentResourceProvider
{
    private final Logger log = LoggerFactory.getLogger(DynamicComponentResourceProviderHandler.class);

    @SuppressWarnings("rawtypes")
    private volatile ServiceRegistration serviceRegistration;

    private String targetRootPath;
    private String providerRootPath;
    private List<String> providedComponentPaths;
    private boolean active;

    //---------- Service Registration

    public long registerService(Bundle bundle, String targetRootPath, String providerRootPath) {
        this.targetRootPath = targetRootPath;
        this.providerRootPath = providerRootPath;
        log.info("Component Path: '{}', Provided Paths: '{}'", targetRootPath, providedComponentPaths);

        final Dictionary<String, Object> props = new Hashtable<>();
        props.put("label", "Dynamic Component Resource: '" + targetRootPath + "'");
        props.put(SERVICE_DESCRIPTION, "Provides the Dynamic Component for '" + targetRootPath + "' resources as synthetic resources");
        props.put(SERVICE_VENDOR, "The Apache Software Foundation");
        props.put(ResourceProvider.PROPERTY_ROOT, targetRootPath);
        props.put(getClass().getName(), bundle.getBundleId());

        log.info("Before Register RARPS with props: '{}'", props);
        serviceRegistration = bundle.getBundleContext().registerService(
            new String[] {ResourceProvider.class.getName(), DynamicComponentResourceProvider.class.getName()}, this, props
        );
        log.info("After Register RARPS, service registration: '{}'", serviceRegistration);
        active = true;
        return (Long) serviceRegistration.getReference().getProperty(Constants.SERVICE_ID);
    }

    public void unregisterService() {
        if (serviceRegistration != null) {
            try {
                serviceRegistration.unregister();
            } catch ( final IllegalStateException ise ) {
                // this might happen on shutdown, so ignore
            }
            serviceRegistration = null;
        }
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public String getTargetRootPath() {
        return targetRootPath;
    }

    @Override
    public List<String> getProvidedComponentPaths() {
        return providedComponentPaths;
    }

    @Override
    public Resource getResource(ResolveContext ctx, String path, ResourceContext resourceContext, Resource parent) {
        ResourceResolver resourceResolver = ctx.getResourceResolver();
        log.info("Get Resource, path: '{}', parent: '{}', provider root: '{}'", path, parent, providerRootPath);
        String resourcePath;
        if(path.startsWith("/")) {
            resourcePath = path;
        } else {
            resourcePath = parent.getPath() + "/" + path;
        }
        Resource answer = null;
        if(resourcePath.startsWith(providerRootPath)) {
            answer = resourceResolver.getResource(resourcePath);
        } else if(resourcePath.equals(targetRootPath)) {
            Resource source = resourceResolver.getResource(providerRootPath);
            answer = createSyntheticFromResource(resourceResolver, source, resourcePath);
        } else if(resourcePath.startsWith(targetRootPath)) {
            int index = resourcePath.lastIndexOf('/');
            if (index > 0 && index < (resourcePath.length() - 1)) {
                String name = resourcePath.substring(index + 1);
                String providedPath = providerRootPath + "/" + name;
                Resource source = resourceResolver.getResource(providedPath);
                if (source != null && !source.isResourceType(RESOURCE_TYPE_NON_EXISTING)) {
                    answer = createSyntheticFromResource(resourceResolver, source, resourcePath);
                }
            }
        } else {
            answer = resourceResolver.getResource(resourcePath);
        }
        log.info("Return resource: '{}'", answer);
        return answer;
    }

    @Override
    public Iterator<Resource> listChildren(ResolveContext ctx, Resource parent) {
        Iterator<Resource> answer;
        log.info("List Children, resolve-context: '{}', parent: '{}'", ctx, parent);
        String resourcePath = parent.getPath();
        ResourceResolver resourceResolver = ctx.getResourceResolver();
        if(resourcePath.equals(providerRootPath)) {
            answer = parent.listChildren();
        } else if(resourcePath.startsWith(targetRootPath)) {
            List<Resource> items = new ArrayList<>();
            Resource provider = resourceResolver.getResource(providerRootPath);
            Iterator<Resource> i = provider.listChildren();
            while(i.hasNext()) {
                Resource child = i.next();
                items.add(createSyntheticFromResource(resourceResolver, child, targetRootPath + "/" + child.getName()));
            }
            answer = items.iterator();
        } else {
            answer = parent.listChildren();
        }
        return answer.hasNext() ? answer : null;
    }

    @Override
    public void start(ProviderContext ctx) {
        log.info("Provider Start, context: '{}'", ctx);
        super.start(ctx);
    }

    @Override
    public void stop() {
        log.info("Provider Stop");
        super.stop();
    }
}
